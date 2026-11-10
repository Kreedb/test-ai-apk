package com.example.minimalapk.examples

import android.content.Context
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.ModelDownload
import java.io.File

/** A demo model fetched on demand; the APK only bundles the pose model. */
data class ExampleModel(
    val id: String,
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

/**
 * Models used by the 示例 tab. Both are the models from TensorFlow's own Lite examples:
 * super_resolution/android and model_personalization/android. Their sizes pin the download so a
 * truncated file is rejected instead of loading garbage.
 */
object ExampleCatalog {
    val superResolution = ExampleModel(
        id = "esrgan",
        name = "ESRGAN 超分辨率",
        url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite",
        sizeBytes = 4_993_712,
    )

    val personalization = ExampleModel(
        id = "personalization",
        name = "设备端训练",
        url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/model_personalization/android/model.tflite",
        sizeBytes = 18_756_528,
    )

    private const val DIR = "examples"

    fun file(context: Context, model: ExampleModel): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, "${model.id}.tflite")
}

internal object ExampleDownload {
    private const val TAG = "ExampleModel"

    /** Downloads once, atomically, and verifies the advertised byte count. */
    fun ensure(context: Context, model: ExampleModel): File {
        val target = ExampleCatalog.file(context, model)
        if (target.length() == model.sizeBytes) return target
        AppLog.i(TAG, "开始下载 ${model.name}（${model.sizeBytes / 1024 / 1024} MB）")
        val bytes = ModelDownload.fetch(model.url)
        require(bytes.size.toLong() == model.sizeBytes) {
            "下载大小不符：期望 ${model.sizeBytes} 字节，实际 ${bytes.size} 字节"
        }
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        AppLog.i(TAG, "已下载 ${model.name}")
        return target
    }
}
