package com.example.minimalapk.examples

import android.content.Context
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.InputSource
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.models.OutputRender
import com.example.minimalapk.models.sha256
import com.example.minimalapk.models.writeAtomically
import com.example.minimalapk.pose.GitHubMirrorPreference
import com.example.minimalapk.pose.ModelDownload
import java.io.File

/** A scene model fetched on demand; the APK only bundles the pose model. */
data class ExampleModel(
    val id: String,
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Exact associated file in the pinned model, downloaded and verified with the weights. */
    val labelFile: String? = null,
    val labelCount: Int = 0,
)

/**
 * Models from TensorFlow Lite examples; only the pose camera model is bundled in the APK.
 */
object ExampleCatalog {
    val superResolution = ExampleModel(
        id = "esrgan",
        name = "ESRGAN 超分辨率",
        url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite",
        sizeBytes = 4_993_712,
        sha256 = "1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b",
    )

    val personalization = ExampleModel(
        id = "personalization",
        name = "设备端训练",
        url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/model_personalization/android/model.tflite",
        sizeBytes = 18_756_528,
        sha256 = "17710c50c02ebccc57381c185b3da0842f4ee1f27fc5a5eb9559b0f9ce0c7038",
    )

    val moveNet = ExampleModel(
        "movenet", "MoveNet 单人姿态估计",
        "https://raw.githubusercontent.com/google-coral/test_data/104342d2d3480b3e66203073dac24f4e2dbb4c41/movenet_single_pose_lightning_ptq.tflite",
        2_894_840, "cd7cc22fa946e5d146a7b98d496853e1923e22828d3972d579973f27f91bb105",
    )
    val classification = ExampleModel(
        "classification", "MobileNet 图片分类",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/image_classification/android/mobilenet_v1_1.0_224_quantized_1_metadata_1.tflite",
        4_287_874, "d3949e8a3556c79739cb675e0be7476503bcce76938031c6a1048e13e0cb7d8b",
        labelFile = "labels.txt", labelCount = 1001,
    )
    val detection = ExampleModel(
        "detection", "SSD 物体检测",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_ssd_mobilenet_v1_1_metadata_2.tflite",
        4_185_175, "cbdecd08b44c5dea3821f77c5468e2936ecfbf43cde0795a2729fdb43401e58b",
        labelFile = "labelmap.txt", labelCount = 90,
    )
    val audio = ExampleModel(
        "audio", "YAMNet 音频分类",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite",
        4_126_810, "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de",
        labelFile = "yamnet_label_list.txt", labelCount = 521,
    )
    val segmentation = ExampleModel(
        "segmentation", "DeepLab 图片分割",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/image_segmentation/android/lite-model_deeplabv3_1_metadata_2.tflite",
        2_780_051, "9711334db2b01d5894feb8ed0f5cb3e97d125b8d229f8d8692f625801818f5ef",
        labelFile = "labelmap.txt", labelCount = 21,
    )
    val configurable = listOf(moveNet, classification, detection, segmentation, audio)

    private const val DIR = "examples"

    fun file(context: Context, model: ExampleModel): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, "${model.id}.tflite")
}

internal object ExampleDownload {
    private const val TAG = "ExampleModel"

    /** The lock prevents rapid tab changes from writing the same cache entry simultaneously. */
    @Synchronized
    fun ensure(context: Context, model: ExampleModel): File {
        val target = ExampleCatalog.file(context, model)
        if (target.length() == model.sizeBytes && target.readBytes().sha256() == model.sha256) return target
        AppLog.i(TAG, "开始下载 ${model.name}（${model.sizeBytes / 1024 / 1024} MB）")
        val bytes = ModelDownload.fetch(model.url, GitHubMirrorPreference.load(context))
        ModelDownload.validateDownloaded(bytes, model.sizeBytes, model.sha256)
        target.writeAtomically(bytes)
        AppLog.i(TAG, "已下载 ${model.name}")
        return target
    }

    /** Never substitute an unrelated TXT file or a label list from a different model version. */
    fun labels(file: File, example: ExampleModel): List<String> = example.labelFile?.let { name ->
        ModelLabels.read(file, name).also {
            require(it.size == example.labelCount) { "${example.name} 标签数不符：需要 ${example.labelCount}，实际 ${it.size}" }
        }
    } ?: emptyList()

    @Synchronized
    fun install(context: Context, example: ExampleModel): ConfiguredModel {
        val file = ensure(context, example)
        // Validate resources before install writes an inferred config. A failed label read must
        // not leave an apparently installed, permanently unlabeled catalog model behind.
        val labels = labels(file, example)
        val directory = ConfiguredModelStore.directory(context)
        val existing = File(directory, "${example.sha256}.properties").exists()
        val model = ConfiguredModelStore.install(directory, example.name, file.readBytes())
        val description = ConfiguredModelStore.describe(model)
        val config = if (existing) {
            ModelConfig.decode(model.configFile.readText(), description)
        } else {
            val inferred = ModelConfig.infer(description.signatures.first())
            inferred.copy(
                inputs = inferred.inputs.map { input -> when (example) {
                    ExampleCatalog.classification, ExampleCatalog.detection, ExampleCatalog.segmentation -> input.copy(mean = 127.5f, std = 127.5f)
                    ExampleCatalog.audio -> input.copy(source = InputSource.AUDIO)
                    else -> input
                } },
                outputs = inferred.outputs.mapIndexed { index, output -> when {
                    example == ExampleCatalog.detection && index == 0 -> output.copy(render = OutputRender.BOXES)
                    example == ExampleCatalog.detection -> output.copy(render = OutputRender.VALUES)
                    example == ExampleCatalog.segmentation -> output.copy(render = OutputRender.SEGMENTATION)
                    else -> output
                } },
            )
        }
        val repaired = repairCatalogLabels(config, example, labels)
        if (!existing || repaired != config) ConfiguredModelStore.saveConfig(model, repaired.encode(), description)
        return model
    }
}

/** Repair older failed/partial installs without resetting user input, output or label choices. */
internal fun repairCatalogLabels(config: ModelConfig, example: ExampleModel, labels: List<String>): ModelConfig {
    if (labels.isEmpty()) return config
    return config.copy(outputs = config.outputs.mapIndexed { index, output ->
        val usesLabels = index == 0 || example == ExampleCatalog.detection && output.render == OutputRender.BOXES
        if (usesLabels && output.labels.isEmpty()) output.copy(labels = labels) else output
    })
}
