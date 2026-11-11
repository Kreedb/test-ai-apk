package com.example.minimalapk.litert.lm

internal enum class LocalTextTask(val label: String) {
    CODE("代码建议"), QUESTION("短问答"), SUMMARY("摘要"), TRANSLATION("翻译"),
}

/** The native bundle owns tokenization and the ChatML template, not the plugin's input widget. */
internal object QwenCoderTemplate {
    const val DEFAULT_SYSTEM = "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."
    val stopTokenIds = listOf(151645, 151643)
    val stopText = listOf("<|im_end|>", "<|endoftext|>")
    private const val USER_PREFIX = "<|im_start|>system\n$DEFAULT_SYSTEM<|im_end|>\n<|im_start|>user\n"
    private const val USER_SUFFIX = "<|im_end|>\n<|im_start|>assistant\n"

    /** For a native render assertion and the byte-token upper bound, never fed to Conversation. */
    fun renderedSingleTurn(userText: String): String = USER_PREFIX + userText + USER_SUFFIX
}

internal data class LocalTextOptions(
    val modelId: String = LocalTextModels.QWEN_ID,
    val task: LocalTextTask = LocalTextTask.CODE,
    val text: String = "Write a Python function that adds two numbers. Code only.",
    val targetLanguage: String = "Chinese",
    val maxOutputTokens: Int = 128,
) {
    fun prepare(): LocalTextRequest {
        require(modelId == LocalTextModels.QWEN_ID) { "此文本引擎尚未验证模型：$modelId" }
        require(maxOutputTokens in 16..LocalTextModels.MAX_OUTPUT_TOKENS) { "输出上限应为 16–256 tokens" }
        require(text.isNotBlank()) { "请填写要处理的文本" }
        require(strictUtf8Bytes(text) <= 768) { "输入超过 768 UTF-8 字节；请缩短文本，不会静默截断" }
        // These are actual tokenizer control tokens; letting input supply them would change roles.
        require(listOf("<|im_start|>", "<|im_end|>", "<|endoftext|>").none { it in text }) {
            "输入不能包含模型的 ChatML 控制标记"
        }
        val prompt = when (task) {
            LocalTextTask.QUESTION -> text
            LocalTextTask.CODE -> "Give a concise code suggestion for this request. Do not execute code.\n\n$text"
            LocalTextTask.SUMMARY -> "Summarize the following text concisely.\n\n$text"
            LocalTextTask.TRANSLATION -> {
                require(targetLanguage.isNotBlank() && strictUtf8Bytes(targetLanguage) <= 48 &&
                    targetLanguage.all { it.isLetter() || it == ' ' || it == '-' }) {
                    "翻译目标语言应为不超过 48 UTF-8 字节的语言名称"
                }
                "Translate the following text into $targetLanguage. Return only the translation.\n\n$text"
            }
        }
        // Qwen's byte-level BPE cannot need more tokens than UTF-8 bytes for this single turn.
        // Count the embedded default system and all template bytes too. This is intentionally a
        // conservative bound, not a fabricated tokenizer count or chars/4 approximation.
        val inputTokenUpperBound = strictUtf8Bytes(QwenCoderTemplate.renderedSingleTurn(prompt))
        require(inputTokenUpperBound + maxOutputTokens <= LocalTextModels.CONTEXT_TOKENS) {
            "文本、模板与输出上限超过 1024-token 窗口的保守字节预算；请缩短输入或降低输出上限"
        }
        return LocalTextRequest(prompt, maxOutputTokens, inputTokenUpperBound)
    }

    fun toConfig(): Map<String, String> = mapOf(
        "model_id" to modelId, "task" to task.name, "text" to text,
        "target_language" to targetLanguage, "max_output_tokens" to maxOutputTokens.toString(),
    )

    companion object {
        fun from(config: Map<String, String>): LocalTextOptions {
            val defaults = LocalTextOptions()
            return LocalTextOptions(
                modelId = config["model_id"] ?: defaults.modelId,
                task = config["task"]?.let { value ->
                    LocalTextTask.entries.firstOrNull { it.name == value }
                        ?: error("未知的本地文本任务：$value")
                } ?: defaults.task,
                text = config["text"] ?: defaults.text,
                targetLanguage = config["target_language"] ?: defaults.targetLanguage,
                maxOutputTokens = config["max_output_tokens"]?.let {
                    it.toIntOrNull() ?: error("输出 token 上限不是整数")
                } ?: defaults.maxOutputTokens,
            )
        }
    }
}

internal data class LocalTextRequest(
    val prompt: String,
    val maxOutputTokens: Int,
    val inputTokenUpperBound: Int,
)

/** Reject unpaired UTF-16 surrogates instead of budgeting Java's replacement '?' as user input. */
internal fun strictUtf8Bytes(value: String): Int {
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val char = value[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() -> {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "文本包含不完整的 Unicode 字符" }
                index++
                4
            }
            char.isLowSurrogate() -> error("文本包含不完整的 Unicode 字符")
            else -> 3
        }
        require(bytes <= Int.MAX_VALUE) { "文本过长" }
        index++
    }
    return bytes.toInt()
}
