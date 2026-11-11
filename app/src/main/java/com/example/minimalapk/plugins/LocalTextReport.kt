package com.example.minimalapk.plugins

/** Seven report slots leave one for status; truncation is explicit and preserves UTF-16 pairs. */
internal fun localTextReportParts(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = mutableListOf<String>()
    var offset = 0
    while (offset < text.length && parts.size < 7) {
        val lastSlot = parts.size == 6
        val truncated = lastSlot && text.length - offset > 240
        var end = minOf(text.length, offset + if (truncated) 220 else 240)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        parts += text.substring(offset, end) + if (truncated) "\n[报告显示已截断]" else ""
        offset = end
    }
    return parts
}
