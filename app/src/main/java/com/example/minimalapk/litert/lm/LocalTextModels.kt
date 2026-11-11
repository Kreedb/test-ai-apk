package com.example.minimalapk.litert.lm

import android.content.Context
import com.example.minimalapk.models.AssetCatalog
import com.example.minimalapk.models.AssetSet
import java.io.File
import java.security.MessageDigest

/** Only bundles with an audited template and a real generation fixture belong in this catalog. */
internal object LocalTextModels {
    const val ASSET_PATH = "models/local-text-models.tsv"
    const val RUNTIME_VERSION = "0.19.0"
    const val QWEN_ID = "local-text-qwen2-5-coder-1-5b"
    const val FILE_NAME = "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm"
    const val REVISION = "7840209bfd393408ef028cf55921d831baa1e4bf"
    const val MODEL_BYTES = 1_117_385_648L
    const val SHA256 = "273ecc7771ba2dd5fe1bb6d4d4726ad0353102f04ad094082ccf59bca9f21213"
    const val MODEL_URL = "https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/resolve/" +
        "$REVISION/$FILE_NAME"

    // The exported model supports 4096; this first mobile recipe deliberately allocates only 1024.
    const val CONTEXT_TOKENS = 1024
    const val MAX_OUTPUT_TOKENS = 256
    // Private packed-weight scratch, CPU2, context1024, max-input + 256 output tokens,
    // four fresh engines on host LM0.19.0: max RSS 1,958,500 KiB. Round UP to 2 GiB + 512 MiB.
    // This is a conservative admission floor, not an Android peak-memory measurement.
    const val MEASURED_PEAK_BYTES = 1_958_500L * 1024
    const val MIN_AVAILABLE_BYTES = 2560L * 1024 * 1024

    fun assetSets(context: Context): List<AssetSet> =
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
            AssetCatalog.parse(it.readText())
        }.also { sets ->
            val set = sets.single()
            check(supports(set)) {
                "本地文本目录与已验证的模型配方不匹配"
            }
        }

    /** Dependency presence alone does not make arbitrary .litertlm recipes/backends executable. */
    fun supports(set: AssetSet): Boolean = set.id == QWEN_ID && set.backend == "cpu" &&
        set.files.singleOrNull()?.let { file ->
            file.name == FILE_NAME && file.bytes == MODEL_BYTES && file.sha256 == SHA256 && file.url == MODEL_URL
        } == true

    /** Generic graph smoke must not pretend a hash check is generation or feed random token IDs. */
    fun selfCheckNote(set: AssetSet?): String = if (set != null && supports(set)) {
        "资源大小/hash 已核验，已集成 LiteRT-LM $RUNTIME_VERSION；需要专用本地文本场景验证" +
            "（固定输入生成、取消、全新引擎再次生成）；本次通用自检未执行 LM 推理"
    } else {
        "资源大小/hash 已读取，已集成 LiteRT-LM $RUNTIME_VERSION，但此 .litertlm 配方未验证；" +
            "需匹配模型的模板、tokenizer、后端和内存门槛，未执行推理"
    }

    /** Hash on the native owner, never the UI. No model-sized array or mmap-to-array conversion. */
    fun verifyFile(file: File, cancellation: LocalTextCancellation) {
        require(file.isFile && file.length() == MODEL_BYTES) {
            "缺少完整的 Qwen2.5-Coder 资源（$MODEL_BYTES 字节）；请从模型资源目录下载"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                cancellation.check()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= MODEL_BYTES) { "本地文本模型大小发生变化，请重新校验资源" }
                digest.update(buffer, 0, count)
            }
        }
        cancellation.check()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(bytes == MODEL_BYTES && hash == SHA256) {
            "本地文本模型 SHA-256 不符；不会加载其它修订或损坏文件"
        }
    }
}
