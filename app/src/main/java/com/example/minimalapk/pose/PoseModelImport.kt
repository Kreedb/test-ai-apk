package com.example.minimalapk.pose

import android.content.Context
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.writeAtomically
import java.io.File
import java.security.MessageDigest

/**
 * The contract this app decodes: float32 NHWC square input and a `5+3K` pose head in either
 * channel-major or anchor-major order.
 */
internal data class PoseContract(
    val inputSize: Int,
    val anchors: Int,
    val keypoints: Int,
    val outputLayout: OutputLayout = OutputLayout.VALUE_MAJOR,
)

/** A best-effort contract plus the checks the user still has to confirm in the model editor. */
internal data class PoseContractGuess(
    val contract: PoseContract,
    val warnings: List<String>,
)

/**
 * Maps one model's I/O tensors onto [PoseContract]. Only a wrong file format, quantization, or a
 * graph that has no `5+3K` pose head is rejected: everything the config can describe (keypoint
 * count, output order, input size, box format) is inferred with a warning and can be corrected in
 * the model editor afterwards. NCHW input, rank-2 output, and quantized I/O would need code, so
 * they ask for a re-export instead.
 */
internal fun inferPoseContract(
    inputShape: IntArray,
    outputShape: IntArray,
    inputFloat32: Boolean,
    outputFloat32: Boolean,
): PoseContractGuess {
    if (!inputFloat32 || !outputFloat32) {
        throw IllegalArgumentException(
            "只支持 float32 输入输出（当前可能是 int8/float16 量化模型），请在导出时去掉量化",
        )
    }
    if (inputShape.size != 4 || inputShape[0] != 1) {
        throw IllegalArgumentException(
            "输入需为四维 [1,H,W,3]，当前 ${inputShape.joinToString()}；请以单张静态图片输入重新导出",
        )
    }
    val inputSize = when {
        inputShape[3] == 3 -> inputShape[1]
        inputShape[1] == 3 -> throw IllegalArgumentException(
            "输入为 NCHW [1,3,H,W]，当前 ${inputShape.joinToString()}；请导出为 NHWC [1,H,W,3]",
        )
        else -> throw IllegalArgumentException(
            "输入的最后一维需为 3（RGB），当前 ${inputShape.joinToString()}",
        )
    }
    if (inputSize <= 0 || inputShape[2] <= 0) {
        throw IllegalArgumentException("输入尺寸未固定（shape 含 -1），请用固定输入尺寸重新导出")
    }
    if (inputSize != inputShape[2]) {
        throw IllegalArgumentException(
            "输入需为正方形，当前 $inputSize×${inputShape[2]}；本应用按正方形 letterbox 处理",
        )
    }
    if (outputShape.size != 3 || outputShape[0] != 1) {
        throw IllegalArgumentException(
            "输出需为三维 [1,C,N] 或 [1,N,C]，当前 ${outputShape.joinToString()}；" +
                "分类/检测模型的输出无法当作姿态头",
        )
    }
    val second = outputShape[1]
    val third = outputShape[2]
    if (second <= 0 || third <= 0) {
        throw IllegalArgumentException("输出维度未固定（shape 含 -1），请用固定输入尺寸重新导出")
    }
    // A `5+3K` channel count identifies which output axis is the channel axis.
    fun keypointsFor(values: Int): Int? =
        if (values >= 8 && (values - 5) % 3 == 0) (values - 5) / 3 else null
    val valueMajor = keypointsFor(second)
    val anchorMajor = keypointsFor(third)
    val outputLayout = when {
        valueMajor != null -> OutputLayout.VALUE_MAJOR
        anchorMajor != null -> OutputLayout.ANCHOR_MAJOR
        else -> throw IllegalArgumentException(
            "输出 ${outputShape.joinToString()} 的通道数不是 5+3K 姿态布局（例如 [1,56,8400] 或 " +
                "[1,8400,56]）；请导出 YOLO-pose 的原始姿态头",
        )
    }
    val keypoints = if (outputLayout == OutputLayout.VALUE_MAJOR) valueMajor!! else anchorMajor!!
    val anchors = if (outputLayout == OutputLayout.VALUE_MAJOR) third else second
    val contract = PoseContract(
        inputSize = inputSize,
        anchors = anchors,
        keypoints = keypoints,
        outputLayout = outputLayout,
    )
    val warnings = buildList {
        if (keypoints != PoseNet.KEYPOINT_COUNT) {
            add(
                "该模型有 $keypoints 个关键点（本应用按 COCO 17 点绘制骨骼与测量），" +
                    "如与模型不符请在编辑页修改关键点数",
            )
        }
        if (outputLayout == OutputLayout.ANCHOR_MAJOR) {
            add("检测到锚点优先输出 [1,N,C]，已自动按该布局解码")
        }
        if (inputSize != 640) {
            add("输入尺寸为 $inputSize×$inputSize，已按该尺寸预处理")
        }
    }
    return PoseContractGuess(contract, warnings)
}

/**
 * Validates a picked file, makes its shape signatures static in the copied bytes, and registers it
 * under `filesDir/models`. Only an unreadable TFLite file fails here; a contract the config can
 * describe installs with [Prepared.warnings] so the editor can fix it. No trial inference: a model
 * that passes this can still fail to compile for an accelerator, which the camera page reports.
 */
internal object PoseModelImport {
    private const val TAG = "ModelImport"

    class Prepared(
        val contract: PoseContract,
        val staticizedSignatures: Int,
        val bytes: ByteArray,
        val warnings: List<String> = emptyList(),
    )

    fun prepare(bytes: ByteArray): Prepared {
        if (!isTflite(bytes)) throw IllegalArgumentException("不是 TFLite 模型（缺少 TFL3 标识）")
        val graph = TfliteGraph(bytes.copyOf())
        val inputs = graph.inputs()
        val outputs = graph.outputs()
        if (inputs.size != 1 || outputs.size != 1) {
            throw IllegalArgumentException(
                "需要单输入单输出模型，当前 ${inputs.size} 个输入 / ${outputs.size} 个输出；" +
                    "请重新导出一个只含单张图片输入与姿态输出的模型",
            )
        }
        val input = inputs.first()
        val output = outputs.first()
        val guess = inferPoseContract(
            inputShape = graph.shape(input),
            outputShape = graph.shape(output),
            inputFloat32 = graph.elementType(input) == FLOAT32,
            outputFloat32 = graph.elementType(output) == FLOAT32,
        )
        val staticized = graph.staticize()
        if (staticized > 0) {
            AppLog.i(TAG, "已把 $staticized 个张量的动态 shape signature 改写为静态")
        }
        return Prepared(guess.contract, staticized, graph.bytes, guess.warnings)
    }

    fun install(context: Context, displayName: String?, prepared: Prepared): ModelSpec {
        val id = sha256(prepared.bytes).take(16)
        val fileName = "$id.tflite"
        val directory = File(context.filesDir, MODELS_DIR).apply { mkdirs() }
        File(directory, fileName).writeAtomically(prepared.bytes)
        val contract = prepared.contract
        return ModelSpec(
            id = id,
            name = sanitizeName(displayName) ?: "导入模型 ${id.take(6)}",
            source = ModelSpec.FILE_PREFIX + fileName,
            inputSize = contract.inputSize,
            anchors = contract.anchors,
            keypoints = contract.keypoints,
            outputLayout = contract.outputLayout,
        )
    }

    fun delete(context: Context, spec: ModelSpec) {
        if (spec.imported) modelFile(context, spec).delete()
    }

    internal fun isTflite(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[4] == 'T'.code.toByte() &&
            bytes[5] == 'F'.code.toByte() &&
            bytes[6] == 'L'.code.toByte() &&
            bytes[7] == '3'.code.toByte()

    private fun sanitizeName(name: String?): String? = name
        ?.substringBeforeLast('.')
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(40)
        ?.takeIf { it.isNotEmpty() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private const val FLOAT32 = 0
}
