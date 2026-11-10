package com.example.minimalapk.pose

import android.content.Context
import com.example.minimalapk.log.AppLog
import java.io.File
import java.security.MessageDigest

/** The contract this app decodes: float32 NHWC square input and a value-major `[1, 5+3K, N]` head. */
internal data class PoseContract(val inputSize: Int, val anchors: Int, val keypoints: Int)

/**
 * Maps one model's I/O tensors onto [PoseContract], or rejects it with a reason shown verbatim in
 * the model page. Only the value-major pose head is supported: anchor-major `[1, N, 5+3K]` would
 * need a transpose the decoder deliberately avoids, and non-float32 I/O has no normalized write
 * path in `TensorBuffer`.
 */
internal fun inferPoseContract(
    inputShape: IntArray,
    outputShape: IntArray,
    inputFloat32: Boolean,
    outputFloat32: Boolean,
): PoseContract {
    if (!inputFloat32 || !outputFloat32) {
        throw IllegalArgumentException("只支持 float32 输入输出（当前可能是 int8/float16 量化模型）")
    }
    if (inputShape.size != 4 || inputShape[0] != 1 || inputShape[3] != 3) {
        throw IllegalArgumentException("输入需为 NHWC [1,H,W,3]，当前 ${inputShape.joinToString()}")
    }
    val size = inputShape[1]
    if (size <= 0) {
        throw IllegalArgumentException("输入尺寸未固定（concrete shape 含 -1），请用固定输入尺寸重新导出")
    }
    if (size != inputShape[2]) {
        throw IllegalArgumentException("输入需为正方形，当前 $size×${inputShape[2]}")
    }
    if (outputShape.size != 3 || outputShape[0] != 1) {
        throw IllegalArgumentException("输出需为 [1,C,N]，当前 ${outputShape.joinToString()}")
    }
    val channels = outputShape[1]
    val anchors = outputShape[2]
    if (anchors <= 0) {
        throw IllegalArgumentException("输出 anchor 数未固定，请用固定输入尺寸重新导出")
    }
    if (channels < 8 || (channels - 5) % 3 != 0) {
        throw IllegalArgumentException(
            "输出通道 $channels 不是 5+3K 值优先姿态布局（若为 [1,N,C] 请重新导出为值优先）",
        )
    }
    // The skeleton edges and the body measurement index COCO keypoints by position, so another
    // keypoint set would silently produce wrong distances instead of missing ones.
    if (channels != 5 + PoseNet.KEYPOINT_COUNT * 3) {
        throw IllegalArgumentException(
            "当前只支持 ${PoseNet.KEYPOINT_COUNT} 个 COCO 关键点（输出通道 56），" +
                "该模型输出 $channels 通道（${(channels - 5) / 3} 点）",
        )
    }
    return PoseContract(inputSize = size, anchors = anchors, keypoints = PoseNet.KEYPOINT_COUNT)
}

/**
 * Validates a picked file, makes its shape signatures static in the copied bytes, and registers it
 * under `filesDir/models`. No trial inference: a model that passes this can still fail to compile
 * for an accelerator, which the camera page reports on open.
 */
internal object PoseModelImport {
    private const val TAG = "ModelImport"

    class Prepared(
        val contract: PoseContract,
        val staticizedSignatures: Int,
        val bytes: ByteArray,
    )

    fun prepare(bytes: ByteArray): Prepared {
        if (!isTflite(bytes)) throw IllegalArgumentException("不是 TFLite 模型（缺少 TFL3 标识）")
        val graph = TfliteGraph(bytes.copyOf())
        val inputs = graph.inputs()
        val outputs = graph.outputs()
        if (inputs.size != 1 || outputs.size != 1) {
            throw IllegalArgumentException("需要单输入单输出模型，当前 ${inputs.size}/${outputs.size}")
        }
        val input = inputs.first()
        val output = outputs.first()
        val contract = inferPoseContract(
            inputShape = graph.shape(input),
            outputShape = graph.shape(output),
            inputFloat32 = graph.elementType(input) == FLOAT32,
            outputFloat32 = graph.elementType(output) == FLOAT32,
        )
        val staticized = graph.staticize()
        if (staticized > 0) {
            AppLog.i(TAG, "已把 $staticized 个张量的动态 shape signature 改写为静态")
        }
        return Prepared(contract, staticized, graph.bytes)
    }

    fun install(context: Context, displayName: String?, prepared: Prepared): ModelSpec {
        val id = sha256(prepared.bytes).take(16)
        val fileName = "$id.tflite"
        val directory = File(context.filesDir, MODELS_DIR).apply { mkdirs() }
        File(directory, fileName).writeBytes(prepared.bytes)
        val contract = prepared.contract
        return ModelSpec(
            id = id,
            name = sanitizeName(displayName) ?: "导入模型 ${id.take(6)}",
            source = ModelSpec.FILE_PREFIX + fileName,
            inputSize = contract.inputSize,
            anchors = contract.anchors,
            keypoints = contract.keypoints,
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
