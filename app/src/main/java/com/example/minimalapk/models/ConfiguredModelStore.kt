package com.example.minimalapk.models

import android.content.Context
import com.example.minimalapk.pose.GraphDescription
import com.example.minimalapk.pose.ModelDownload
import com.example.minimalapk.pose.TfliteGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class ConfiguredModel(val id: String, val name: String, val file: File) {
    val configFile: File get() = File(file.parentFile, "$id.properties")
    val nameFile: File get() = File(file.parentFile, "$id.name")
}

internal object ConfiguredModelStore {
    private val mutableRevision = MutableStateFlow(0L)
    /** Successful atomic changes invalidate camera consumers even when the model ID is unchanged. */
    val revision = mutableRevision.asStateFlow()

    fun directory(context: Context): File = File(context.filesDir, "configured_models")

    fun list(directory: File): List<ConfiguredModel> = directory.listFiles().orEmpty()
        .filter { it.extension == "tflite" && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}")) }
        .map { file ->
            val model = ConfiguredModel(file.nameWithoutExtension, file.nameWithoutExtension.take(8), file)
            model.copy(name = if (model.nameFile.isFile) model.nameFile.readText().take(80) else model.name)
        }.sortedBy { it.name }

    fun install(directory: File, name: String?, bytes: ByteArray): ConfiguredModel {
        require(bytes.size <= ModelDownload.MAX_BYTES) { "模型超过 64 MB" }
        // Inspect without creating an Interpreter: a valid model can be kept even if ops are missing.
        val description = TfliteGraph(bytes).describe()
        val id = bytes.sha256()
        val model = ConfiguredModel(id, name?.replace(Regex("\\s+"), " ")?.take(80)?.ifBlank { null } ?: "模型 ${id.take(8)}", File(directory, "$id.tflite"))
        if (!model.configFile.exists()) {
            model.configFile.writeAtomically(ModelConfig.infer(description.signatures.first()).encode().toByteArray())
        }
        model.nameFile.writeAtomically(model.name.toByteArray())
        if (!model.file.exists()) model.file.writeAtomically(bytes)
        mutableRevision.update { it + 1 }
        return model
    }

    fun describe(model: ConfiguredModel): GraphDescription = TfliteGraph(model.file.readBytes()).describe()

    fun saveConfig(model: ConfiguredModel, text: String, description: GraphDescription) {
        ModelConfig.decode(text, description)
        model.configFile.writeAtomically(text.toByteArray())
        mutableRevision.update { it + 1 }
    }

    fun delete(model: ConfiguredModel) {
        check(!model.file.exists() || model.file.delete()) { "无法删除模型" }
        model.configFile.delete()
        model.nameFile.delete()
        mutableRevision.update { it + 1 }
    }
}
