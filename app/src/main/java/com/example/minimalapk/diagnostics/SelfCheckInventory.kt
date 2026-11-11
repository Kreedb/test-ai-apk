package com.example.minimalapk.diagnostics

import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleModel
import com.example.minimalapk.models.AssetSet
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.pose.ModelCatalog
import com.example.minimalapk.pose.ModelSpec
import com.example.minimalapk.pose.ModelStore
import java.io.File

internal enum class CheckKind { POSE, CONFIGURED, EXAMPLE, CATALOG, RESOURCE, UNREGISTERED, REFERENCE, INVENTORY }

internal data class CheckTarget(
    val id: String,
    val name: String,
    val kind: CheckKind,
    val file: File? = null,
    val assetPath: String? = null,
    val pose: ModelSpec? = null,
    val configured: ConfiguredModel? = null,
    val example: ExampleModel? = null,
    val set: AssetSet? = null,
    val expectedBytes: Long? = null,
    val expectedHash: String? = null,
    val note: String = "",
    val inventoryError: Throwable? = null,
) {
    val source: String get() = assetPath?.let { "asset:$it" } ?: file?.absolutePath ?: "catalog"
}

internal val selfCheckExamples: List<ExampleModel>
    get() = (listOf(ExampleCatalog.superResolution, ExampleCatalog.personalization) + ExampleCatalog.configurable)
        .distinctBy { it.id }

/** Read-only inventory: disabled models, dangling configs and unregistered model files count too. */
internal fun selfCheckInventory(
    filesDir: File,
    poses: List<ModelSpec>,
    sets: List<AssetSet>,
): List<CheckTarget> = buildList {
    fun files(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        val entries = directory.listFiles()
        if (entries == null) {
            add(CheckTarget("inventory:${directory.relativeTo(filesDir).path}", directory.name, CheckKind.INVENTORY,
                inventoryError = IllegalStateException("无法枚举 ${directory.absolutePath}；该目录的模型未检查")))
        }
        return entries?.toList().orEmpty()
    }
    val poseDirectory = File(filesDir, "models")
    val allPoses = (poses + ModelStore.builtIn.filter { builtIn -> poses.none { it.id == builtIn.id } })
        .distinctBy { it.id }
    allPoses.forEach { spec ->
        add(CheckTarget(
            id = "pose:${spec.id}", name = spec.name, kind = CheckKind.POSE,
            file = spec.fileName?.let { File(poseDirectory, it) }, assetPath = spec.assetPath, pose = spec,
        ))
    }
    files(poseDirectory).filter { it.extension.equals("tflite", true) }
        .sortedBy { it.name }.filter { file -> allPoses.none { it.fileName == file.name } }.forEach { file ->
            add(CheckTarget("pose-file:${file.name}", file.name, CheckKind.UNREGISTERED, file = file,
                note = "未登记的本地模型；按文件张量进行 CPU 冒烟检查"))
        }

    val configuredDirectory = File(filesDir, "configured_models")
    val configuredFiles = files(configuredDirectory)
    val ids = configuredFiles.filter {
        it.extension.equals("tflite", true) ||
            (it.extension in listOf("properties", "name") && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}")))
    }.map { it.nameWithoutExtension }.distinct().sorted()
    ids.forEach { id ->
        val file = configuredFiles.firstOrNull { it.nameWithoutExtension == id && it.extension.equals("tflite", true) }
            ?: File(configuredDirectory, "$id.tflite")
        // Names are cosmetic; reading a corrupt name file must not hide an installed model.
        val model = ConfiguredModel(id, id.take(12), file)
        add(CheckTarget(
            id = "configured:$id", name = model.name, kind = CheckKind.CONFIGURED,
            file = file, configured = model,
            expectedHash = id.takeIf { it.matches(Regex("[a-f0-9]{64}")) },
            example = selfCheckExamples.firstOrNull { it.sha256 == id },
        ))
    }

    // Keep catalog references even if the user renamed/staticized an imported copy. A reference
    // never counts as inference PASS; every actual installed copy above is checked independently.
    ModelCatalog.models.forEachIndexed { index, remote ->
        val installed = allPoses.filter { it.name == remote.name }.map { "pose:${it.id}" } +
            ids.filter { it == remote.sha256 }.map { "configured:$it" }
        add(CheckTarget(
            id = "downloadable-pose:$index", name = remote.name, kind = CheckKind.REFERENCE,
            expectedBytes = remote.sizeBytes, expectedHash = remote.sha256,
            note = if (installed.isEmpty()) {
                "未找到此目录项的安装记录；请在自定义模型中下载；重命名的本地副本另按文件检查"
            } else {
                "目录引用，实际副本单独检查：${installed.joinToString()}；姿态导入静态化后 hash 可不同"
            },
        ))
    }

    val exampleDirectory = File(filesDir, "examples")
    selfCheckExamples.forEach { example ->
        add(CheckTarget(
            id = "example:${example.id}", name = example.name, kind = CheckKind.EXAMPLE,
            file = File(exampleDirectory, "${example.id}.tflite"), example = example,
            expectedBytes = example.sizeBytes, expectedHash = example.sha256,
        ))
    }
    files(exampleDirectory).filter { it.extension.equals("tflite", true) }
        .sortedBy { it.name }.filter { file -> selfCheckExamples.none { "${it.id}.tflite" == file.name } }
        .forEach { file ->
            add(CheckTarget("example-file:${file.name}", file.name, CheckKind.UNREGISTERED, file = file))
        }
    val checkpoint = File(exampleDirectory, "personalization-head.bin")
    if (checkpoint.exists()) {
        add(CheckTarget("resource:personalization-head", "设备端学习分类头存档", CheckKind.RESOURCE,
            file = checkpoint, note = "只读恢复分类头并使用合成特征验证输出；不训练、不保存存档"))
    }

    val assetRoot = File(filesDir, "model_assets")
    sets.forEach { set ->
        set.files.forEach { asset ->
            add(CheckTarget(
                id = "catalog:${set.id}/${asset.name}", name = "${set.name} / ${asset.name}",
                kind = CheckKind.CATALOG, file = File(File(assetRoot, set.id), asset.name), set = set,
                expectedBytes = asset.bytes, expectedHash = asset.sha256,
            ))
        }
    }
    // Also cover installed files left behind by a catalog upgrade; partial/staging dirs are not
    // installations and are deliberately untouched.
    files(assetRoot).filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name }.forEach { directory ->
            files(directory).filter {
                it.extension.lowercase() in listOf("tflite", "litertlm") &&
                    sets.none { set -> set.id == directory.name && set.files.any { asset -> asset.name == it.name } }
            }.sortedBy { it.name }.forEach { file ->
                add(CheckTarget("catalog-file:${directory.name}/${file.name}", file.name,
                    CheckKind.UNREGISTERED, file = file))
            }
        }
}

internal enum class CheckStatus { PASS, FAIL, SKIP }

internal data class CheckResult(
    val id: String,
    val status: CheckStatus,
    val stage: String,
    val detail: String,
    val timingsMs: Map<String, Long> = emptyMap(),
    val inferenceRan: Boolean = false,
    val validatedOutputs: Int = 0,
) {
    init {
        require(status != CheckStatus.PASS || (inferenceRan && validatedOutputs > 0)) {
            "PASS requires real inference and validated outputs; readiness is not inference"
        }
    }

    fun logLine(): String = "id=$id status=$status stage=$stage inference=$inferenceRan " +
        "validatedOutputs=$validatedOutputs timingsMs=$timingsMs detail=$detail"
}

internal data class SelfCheckState(
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val runId: String = "",
    val current: String = "",
    val total: Int = 0,
    val results: List<CheckResult> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val summary: String get() = "PASS ${results.count { it.status == CheckStatus.PASS }} · " +
        "FAIL ${results.count { it.status == CheckStatus.FAIL }} · " +
        "SKIP ${results.count { it.status == CheckStatus.SKIP }}"
}

internal class CheckSkipped(message: String) : Exception(message)

/** Never invoke persistence/training endpoints, even if they are the saved active signature. */
internal fun signatureSkipReason(key: String, inputNames: List<String>, personalization: Boolean): String? {
    if (personalization && key == "load") return null // Frozen feature extractor, not checkpoint I/O.
    val unsafe = Regex("train|save|restore|checkpoint|assign|update|reset|initializ|load", RegexOption.IGNORE_CASE)
    if (unsafe.containsMatchIn(key) || inputNames.any {
        Regex("checkpoint|file.?path|filename|save.?path", RegexOption.IGNORE_CASE).containsMatchIn(it)
    }) return "签名 $key 涉及训练/状态或文件路径，未调用；请选择无副作用的推理签名"
    return null
}

internal fun chooseCheckSignature(keys: List<String>, configured: String?, personalization: Boolean): String {
    if (configured != null) return configured
    if (personalization) {
        require("load" in keys) { "设备端学习模型缺少 load 特征提取签名" }
        return "load"
    }
    if (keys.isEmpty()) return ""
    val safe = keys.filter { signatureSkipReason(it, emptyList(), false) == null }
    return listOf("serving_default", "predict", "infer", "inference").firstOrNull { it in safe }
        ?: safe.firstOrNull()
        ?: throw CheckSkipped("全部签名均为训练/状态操作：$keys；未调用主子图以免绕过保护")
}

internal fun unsupportedRuntimeReason(error: Throwable): String? {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val messages = causes.joinToString("; ") { it.message.orEmpty() }
    if (causes.any { it is UnsatisfiedLinkError }) {
        return "当前 ABI/安装缺少可加载的原生运行时：$messages；请安装匹配 ABI 的完整 APK，未执行推理"
    }
    val missingOperator = listOf("not a registered custom op", "unresolved custom op", "select tf ops",
        "Flex delegate", "Didn't find op for builtin opcode", "not supported by this interpreter")
        .any { messages.contains(it, ignoreCase = true) }
    return if (missingOperator) {
        "当前 CPU 运行时缺少模型算子/委托：$messages；需对应运行时或重新导出兼容模型，未验证推理"
    } else null
}
