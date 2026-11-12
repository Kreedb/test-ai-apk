package com.example.minimalapk.models

import android.content.Context
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.ModelDownload
import com.example.minimalapk.pose.TfliteGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class ConfiguredModel(
    val id: String,
    val name: String,
    val file: File,
    val enabled: Boolean = false,
) {
    val configFile: File get() = File(file.parentFile, "$id.properties")
    val nameFile: File get() = File(file.parentFile, "$id.name")
    val enabledFile: File get() = File(file.parentFile, "$id.enabled")
}

internal object ConfiguredModelStore {
    private val mutableRevision = MutableStateFlow(0L)
    private val generations = mutableMapOf<String, Long>()
    /** Successful atomic changes invalidate camera consumers even when the model ID is unchanged. */
    val revision = mutableRevision.asStateFlow()

    fun directory(context: Context): File = File(context.filesDir, "configured_models")

    @Synchronized
    fun list(directory: File): List<ConfiguredModel> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "tflite" && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}")) }
        .map { file ->
            val model = ConfiguredModel(file.nameWithoutExtension, file.nameWithoutExtension.take(8), file)
            model.copy(
                name = if (model.nameFile.isFile) model.nameFile.readText().take(80) else model.name,
                enabled = model.enabledFile.isFile && model.enabledFile.readText().trim() == "true",
            )
        }.sortedBy { it.name }

    @Synchronized
    fun install(
        directory: File,
        name: String?,
        bytes: ByteArray,
        initialConfig: (GraphDescription) -> ModelConfig = { ModelConfig.infer(it.signatures.first()) },
    ): ConfiguredModel {
        require(bytes.size <= ModelDownload.MAX_BYTES) { "模型超过 64 MB" }
        // Inspect without creating an Interpreter: a valid model can be kept even if ops are missing.
        val description = TfliteGraph(bytes).describe()
        val id = bytes.sha256()
        val candidate = ConfiguredModel(id, name?.replace(Regex("\\s+"), " ")?.take(80)?.ifBlank { null } ?: "模型 ${id.take(8)}", File(directory, "$id.tflite"))
        ConfiguredModelAccess.checkAvailable(candidate)
        val model = candidate.copy(name = if (candidate.nameFile.isFile) candidate.nameFile.readText().take(80) else candidate.name)
        val targets = listOf(model.configFile, model.nameFile, model.file)
        check(targets.all { !it.exists() || it.isFile }) { "模型存储位置不是文件，原有内容已保留" }
        val created = mutableListOf<File>()
        try {
            fun create(file: File, bytes: () -> ByteArray) {
                if (!file.exists()) {
                    file.writeAtomically(bytes())
                    created += file
                }
            }
            create(model.configFile) { initialConfig(description).encode().toByteArray() }
            create(model.nameFile) { model.name.toByteArray() }
            // The weight file is the list's visibility marker. Failed new installs leave no
            // orphan properties/name, and re-imports never rewrite any existing sidecar.
            create(model.file) { bytes }
        } catch (error: Throwable) {
            created.asReversed().forEach { it.delete() }
            throw error
        }
        if (created.isNotEmpty()) mutableRevision.update { it + 1 }
        return model.copy(enabled = model.enabledFile.isFile && model.enabledFile.readText().trim() == "true")
    }

    fun describe(model: ConfiguredModel): GraphDescription = TfliteGraph(model.file.readBytes()).describe()

    /** Cheap structural/protocol status only; native operator readiness is proved by a real run. */
    fun readiness(model: ConfiguredModel): String = try {
        val description = describe(model)
        val config = ModelConfig.decode(model.configFile.readText(), description)
        configuredInputContract(config, description.signatures.single { it.key == config.signature }).description +
            "；原生算子兼容性以实际运行结果为准"
    } catch (error: Exception) { "未就绪：${error.message ?: "模型或配置无法读取"}；点击卡片修正或重新导入" }

    @Synchronized
    fun version(model: ConfiguredModel): ConfiguredModelVersion? {
        if (!model.file.isFile || !model.configFile.isFile) return null
        return ConfiguredModelVersion(model.id, model.file.absolutePath, model.configFile.readBytes().sha256(),
            model.enabledFile.isFile && model.enabledFile.readText().trim() == "true",
            generations[model.file.absolutePath] ?: 0L)
    }

    private fun invalidate(model: ConfiguredModel) {
        val path = model.file.absolutePath
        generations[path] = (generations[path] ?: 0L) + 1
        ConfiguredModelResults.invalidate(model)
        mutableRevision.update { it + 1 }
    }

    /** A separate atomic sidecar keeps camera selection out of the user's version-1 properties. */
    @Synchronized
    fun setEnabled(model: ConfiguredModel, enabled: Boolean) {
        ConfiguredModelAccess.checkAvailable(model)
        check(model.file.isFile) { "模型已删除，请重新导入" }
        if (enabled) {
            // Non-image models are enabled consumers too: explicit PCM runs live, others on demand.
            // Decode still rejects an invalid signature/shape; runtime limitations are diagnosed per model.
            ModelConfig.decode(model.configFile.readText(), describe(model))
        }
        if (model.enabledFile.isFile && model.enabledFile.readText().trim() == enabled.toString()) return
        model.enabledFile.writeAtomically(enabled.toString().toByteArray())
        ConfiguredModelAccess.cancel(model)
        invalidate(model)
    }

    /**
     * Upgrade seam for PluginSettingsStore: call before removing the old `model_id` preference.
     * An explicit new toggle (including false) always wins. Missing files are never recreated.
     * Returns whether the legacy ID refers to an installed model; no model/config/checkpoint moves.
     */
    @Synchronized
    fun migrateLegacySelection(directory: File, legacyId: String): Boolean {
        val model = list(directory).firstOrNull { it.id == legacyId } ?: return false
        if (!model.enabledFile.exists()) {
            model.enabledFile.writeAtomically("true".toByteArray())
            invalidate(model)
        }
        return true
    }

    @Synchronized
    fun saveConfig(model: ConfiguredModel, text: String, description: GraphDescription) {
        ConfiguredModelAccess.checkAvailable(model)
        ModelConfig.decode(text, description)
        check(model.file.isFile) { "模型已删除，请重新导入" }
        model.configFile.writeAtomically(text.toByteArray())
        ConfiguredModelAccess.cancel(model)
        invalidate(model)
    }

    fun delete(model: ConfiguredModel, transfer: AssetTransfer = AssetTransfer()) {
        transfer.checkpoint()
        try {
            ConfiguredModelAccess.delete(model) { drain ->
                // Withdraw results before draining native readers, never unlink under a live runner.
                synchronized(this) { invalidate(model) }
                // Cancel while draining without holding the store monitor; observers need it to
                // stop/reconcile, and a pending manual task may still be reading its version.
                drain()
                synchronized(this) {
                    transfer.checkpoint()
                    val files = listOf(model.file, model.configFile, model.nameFile, model.enabledFile).filter { it.exists() }
                    check(files.all { it.isFile }) { "模型或配置路径不是文件，删除失败，原有内容已保留" }
                    if (files.isNotEmpty()) {
                        val staging = File.createTempFile(".delete-${model.id.take(8)}-", ".tmp", model.file.parentFile)
                        check(staging.delete() && staging.mkdir()) { "无法准备删除，原文件已保留" }
                        val moved = mutableListOf<Pair<File, File>>()
                        try {
                            files.forEach { file ->
                                val target = File(staging, file.name)
                                check(file.renameTo(target)) { "无法删除 ${file.name}，原文件已保留" }
                                moved += file to target
                            }
                        } catch (error: Throwable) {
                            moved.asReversed().forEach { (original, staged) ->
                                if (!staged.renameTo(original)) error.addSuppressed(IllegalStateException("恢复失败：${staged.absolutePath}"))
                            }
                            staging.delete()
                            throw error
                        }
                        // All entries are now removed together. Cleanup is after the accepted
                        // rename transaction, so cancellation cannot expose a half-deleted model.
                        if (!staging.deleteRecursively()) AppLog.w("Models", "模型已移除，但临时删除目录清理失败：${staging.name}")
                    }
                }
            }
        } finally { mutableRevision.update { it + 1 } }
    }
}
