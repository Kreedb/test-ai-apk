package com.example.minimalapk.models

import android.content.Context
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
    /** Successful atomic changes invalidate camera consumers even when the model ID is unchanged. */
    val revision = mutableRevision.asStateFlow()

    fun directory(context: Context): File = File(context.filesDir, "configured_models")

    @Synchronized
    fun list(directory: File): List<ConfiguredModel> = directory.listFiles().orEmpty()
        .filter { it.extension == "tflite" && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}")) }
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
        val model = ConfiguredModel(id, name?.replace(Regex("\\s+"), " ")?.take(80)?.ifBlank { null } ?: "模型 ${id.take(8)}", File(directory, "$id.tflite"))
        if (!model.configFile.exists()) {
            model.configFile.writeAtomically(initialConfig(description).encode().toByteArray())
        }
        model.nameFile.writeAtomically(model.name.toByteArray())
        if (!model.file.exists()) model.file.writeAtomically(bytes)
        mutableRevision.update { it + 1 }
        return model.copy(enabled = model.enabledFile.isFile && model.enabledFile.readText().trim() == "true")
    }

    fun describe(model: ConfiguredModel): GraphDescription = TfliteGraph(model.file.readBytes()).describe()

    /** A separate atomic sidecar keeps camera selection out of the user's version-1 properties. */
    @Synchronized
    fun setEnabled(model: ConfiguredModel, enabled: Boolean) {
        check(model.file.isFile) { "模型已删除，请重新导入" }
        if (enabled) {
            val config = ModelConfig.decode(model.configFile.readText(), describe(model))
            require(config.inputs.all { it.source == InputSource.IMAGE }) {
                "此模型需要文件输入，请点击模型卡片配置并运行；相机仅支持全部为 IMAGE 的输入"
            }
        }
        if (model.enabledFile.isFile && model.enabledFile.readText().trim() == enabled.toString()) return
        model.enabledFile.writeAtomically(enabled.toString().toByteArray())
        mutableRevision.update { it + 1 }
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
            mutableRevision.update { it + 1 }
        }
        return true
    }

    @Synchronized
    fun saveConfig(model: ConfiguredModel, text: String, description: GraphDescription) {
        ModelConfig.decode(text, description)
        check(model.file.isFile) { "模型已删除，请重新导入" }
        model.configFile.writeAtomically(text.toByteArray())
        mutableRevision.update { it + 1 }
    }

    @Synchronized
    fun delete(model: ConfiguredModel) {
        check(!model.file.exists() || model.file.delete()) { "无法删除模型" }
        model.configFile.delete()
        model.nameFile.delete()
        model.enabledFile.delete()
        mutableRevision.update { it + 1 }
    }
}
