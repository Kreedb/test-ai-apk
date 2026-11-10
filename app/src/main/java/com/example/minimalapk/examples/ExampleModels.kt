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
import com.example.minimalapk.pose.ModelDownload
import com.example.minimalapk.pose.readCapped
import java.io.File
import java.util.zip.ZipFile

/** A demo model fetched on demand; the APK only bundles the pose model. */
data class ExampleModel(
    val id: String,
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
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
    )
    val detection = ExampleModel(
        "detection", "SSD 物体检测",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_ssd_mobilenet_v1_1_metadata_2.tflite",
        4_185_175, "cbdecd08b44c5dea3821f77c5468e2936ecfbf43cde0795a2729fdb43401e58b",
    )
    val audio = ExampleModel(
        "audio", "YAMNet 音频分类",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite",
        4_126_810, "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de",
    )
    val segmentation = ExampleModel(
        "segmentation", "DeepLab 图片分割",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/image_segmentation/android/lite-model_deeplabv3_1_metadata_2.tflite",
        2_780_051, "9711334db2b01d5894feb8ed0f5cb3e97d125b8d229f8d8692f625801818f5ef",
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
        val bytes = ModelDownload.fetch(model.url)
        require(bytes.size.toLong() == model.sizeBytes) {
            "下载大小不符：期望 ${model.sizeBytes} 字节，实际 ${bytes.size} 字节"
        }
        require(bytes.sha256() == model.sha256) { "模型 SHA-256 不符，请重新下载" }
        target.writeAtomically(bytes)
        AppLog.i(TAG, "已下载 ${model.name}")
        return target
    }

    @Synchronized
    fun install(context: Context, example: ExampleModel): ConfiguredModel {
        val file = ensure(context, example)
        val directory = ConfiguredModelStore.directory(context)
        val existing = File(directory, "${example.sha256}.properties").exists()
        val model = ConfiguredModelStore.install(directory, example.name, file.readBytes())
        if (!existing) {
            val description = ConfiguredModelStore.describe(model)
            val inferred = ModelConfig.infer(description.signatures.first())
            val labels = if (example in listOf(ExampleCatalog.classification, ExampleCatalog.detection, ExampleCatalog.audio)) {
                ZipFile(file).use { zip ->
                    val entry = zip.entries().asSequence().first { it.name.endsWith(".txt") }
                    zip.getInputStream(entry).use { it.readCapped(1024 * 1024) }.toString(Charsets.UTF_8).trimEnd().lines()
                }
            } else emptyList()
            val config = inferred.copy(
                inputs = inferred.inputs.map { input -> when (example) {
                    ExampleCatalog.classification, ExampleCatalog.detection, ExampleCatalog.segmentation -> input.copy(mean = 127.5f, std = 127.5f)
                    ExampleCatalog.audio -> input.copy(source = InputSource.AUDIO)
                    else -> input
                } },
                outputs = inferred.outputs.mapIndexed { index, output -> when {
                    example == ExampleCatalog.detection && index == 0 -> output.copy(render = OutputRender.BOXES, labels = labels)
                    example == ExampleCatalog.detection -> output.copy(render = OutputRender.VALUES)
                    example == ExampleCatalog.segmentation -> output.copy(render = OutputRender.SEGMENTATION)
                    else -> output.copy(labels = labels)
                } },
            )
            ConfiguredModelStore.saveConfig(model, config.encode(), description)
        }
        return model
    }
}
