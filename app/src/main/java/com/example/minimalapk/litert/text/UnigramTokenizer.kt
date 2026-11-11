/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

// Adapted from typed_decisions/deberta/DecisionTokenizer.kt and gliner/GlinerTokenizer.kt
// in google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8.
// Open-Decision host: litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT@
// 7a276235b795e8ad3ae7ac6a9f237daa2098863a/android/sample/app/src/main/java/com/opendecision/.
// GLiNER host: litert-community/GLiNER2.5-Decide-LiteRT@
// db80197282d11373df084c0ceed67a54544cfa84/android/app/src/main/java/com/gliner25decide/.
// Apache-2.0; see ATTRIBUTION.md. Changes: shared lattice, separate validated normalizers,
// HostJson instead of org.json, malformed charsmap checks, API-24-safe Base64 decoding.
package com.example.minimalapk.litert.text

import java.io.File
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

internal enum class UnigramFamily { OPEN_DECISION, GLINER_DECIDE }

/** Double-score Viterbi Unigram with normalized/raw added-token passes and no byte fallback. */
internal class UnigramTokenizer internal constructor(
    root: JsonObject,
    private val family: UnigramFamily,
    expectedBaseVocabulary: Int,
) {
    constructor(file: File, family: UnigramFamily) : this(HostJson.obj(HostJson.parse(file)), family, 128_000)

    private data class Piece(val id: Int, val score: Double)
    private val vocabulary = HashMap<String, Piece>()
    private val raw = AddedTokens()
    private val normalized = AddedTokens()
    private val addedIds = HashMap<String, Int>()
    private val maxPieceLength: Int
    private val unknownScore: Double
    private val unknownId: Int
    private val charsmap: PrecompiledCharsmap?
    val padId: Int
    val vocabularySize: Int

    init {
        val model = root.obj("model")
        require(model["type"] == "Unigram" && model["byte_fallback"] != true) {
            "$family requires the published Unigram tokenizer without byte fallback."
        }
        charsmap = validateNormalizer(root)
        unknownId = model["unk_id"].integer("Unigram unk_id")
        val vocab = model.array("vocab")
        require(vocab.size == expectedBaseVocabulary && unknownId in vocab.indices) {
            "$family base vocabulary must have $expectedBaseVocabulary entries and a valid unk_id."
        }
        var minScore = Double.POSITIVE_INFINITY
        var maxLength = 0
        vocab.forEachIndexed { id, value ->
            val entry = HostJson.array(value)
            require(entry.size == 2 && entry[0] is String && entry[1] is Number) { "Malformed Unigram piece $id." }
            val text = entry[0] as String
            val score = (entry[1] as Number).toDouble()
            require(text.isNotEmpty() && text.length <= 1024 && score.isFinite()) { "Invalid Unigram piece $id." }
            require(vocabulary.put(text, Piece(id, score)) == null) { "Duplicate Unigram piece '$text'." }
            minScore = minOf(minScore, score)
            maxLength = maxOf(maxLength, text.length)
        }
        var size = vocab.size
        root.array("added_tokens").forEach { value ->
            val entry = HostJson.obj(value)
            require(entry["lstrip"] == false && entry["rstrip"] == false && entry["single_word"] == false) {
                "Unsupported $family added-token matching policy."
            }
            val content = entry.string("content")
            val id = entry["id"].integer("Added-token id")
            require(id < 128_100) { "$family token id $id exceeds the supported tables." }
            if (entry.boolean("normalized")) normalized.insert(normalize(content), id)
            else raw.insert(content, id)
            require(addedIds.put(content, id) == null) { "Duplicate added token '$content'." }
            size = maxOf(size, id + 1)
        }
        vocabularySize = size
        padId = requireNotNull(addedIds["[PAD]"]) { "Missing [PAD] token." }
        maxPieceLength = maxLength
        unknownScore = minScore - 10.0
        if (expectedBaseVocabulary == 128_000) {
            val markers = when (family) {
                UnigramFamily.OPEN_DECISION -> mapOf("[STATE]" to 128001, "[Q]" to 128002, "[OPT]" to 128003)
                UnigramFamily.GLINER_DECIDE -> mapOf(
                    "[SEP_STRUCT]" to 128001, "[SEP_TEXT]" to 128002, "[P]" to 128003,
                    "[L]" to 128007, "[DESCRIPTION]" to 128010,
                )
            }
            require(markers.all { (key, id) -> addedIds[key] == id } && padId == 0 &&
                addedIds["[CLS]"] == 1 && addedIds["[SEP]"] == 2 && unknownId == 3 &&
                addedIds["[MASK]"] == 128000 && addedIds["[UNK]"] == 3 &&
                vocabularySize == if (family == UnigramFamily.OPEN_DECISION) 128004 else 128011) {
                "$family special-token ids differ from the pinned graph contract."
            }
        }
    }

    fun tokenId(token: String): Int = requireNotNull(addedIds[token]) { "Missing $family token $token." }

    /** No automatic CLS/SEP. GLiNER calls this independently for EACH schema/text word. */
    fun encode(text: String): IntArray {
        val output = ArrayList<Int>()
        raw.split(text, { segment ->
            normalized.split(normalize(segment), { metaspace(it, output) }, { output.add(it) })
        }, { output.add(it) })
        return output.toIntArray()
    }

    fun normalize(text: String): String = when (family) {
        UnigramFamily.OPEN_DECISION -> SPACES.matcher(
            checkNotNull(charsmap).normalize(text.trim { unicodeWhitespace(it.code) }),
        ).replaceAll(" ")
        UnigramFamily.GLINER_DECIDE -> Normalizer.normalize(
            REPEATED_WHITESPACE.matcher(text).replaceAll(" "), Normalizer.Form.NFC,
        ).trimEnd { unicodeWhitespace(it.code) }
    }

    private fun metaspace(text: String, output: MutableList<Int>) {
        if (text.isEmpty()) return
        val escaped = text.replace(' ', '▁')
        val prefixed = if (escaped.startsWith('▁')) escaped else "▁$escaped"
        var start = 0
        for (index in 1 until prefixed.length) if (prefixed[index] == '▁') {
            output.addAll(viterbi(prefixed.substring(start, index)))
            start = index
        }
        output.addAll(viterbi(prefixed.substring(start)))
    }

    private fun viterbi(text: String): List<Int> {
        val best = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(text.length + 1)
        val ids = IntArray(text.length + 1)
        best[0] = 0.0
        var position = 0
        while (position < text.length) {
            val length = Character.charCount(text.codePointAt(position))
            var hasSingle = false
            if (best[position] != Double.NEGATIVE_INFINITY) {
                for (end in position + 1..minOf(text.length, position + maxPieceLength)) {
                    val piece = vocabulary[text.substring(position, end)] ?: continue
                    if (end - position == length) hasSingle = true
                    val score = best[position] + piece.score
                    if (score > best[end]) {
                        best[end] = score
                        back[end] = position
                        ids[end] = piece.id
                    }
                }
                if (!hasSingle) {
                    val end = position + length
                    val score = best[position] + unknownScore
                    if (score > best[end]) {
                        best[end] = score
                        back[end] = position
                        ids[end] = unknownId
                    }
                }
            }
            position += length
        }
        val reversed = ArrayList<Int>()
        position = text.length
        while (position > 0) {
            check(best[position].isFinite() && back[position] < position) { "Unigram lattice has no path." }
            val id = ids[position]
            if (id != unknownId || reversed.lastOrNull() != unknownId) reversed.add(id)
            position = back[position]
        }
        reversed.reverse()
        return reversed
    }

    private fun validateNormalizer(root: JsonObject): PrecompiledCharsmap? {
        val normalizer = root.obj("normalizer")
        require(normalizer["type"] == "Sequence") { "$family normalizer must be a Sequence." }
        val norms = normalizer.array("normalizers").map(HostJson::obj)
        require(norms.size == 3) { "$family needs exactly three normalization stages." }
        val result = when (family) {
            UnigramFamily.OPEN_DECISION -> {
                require(norms[0]["type"] == "Strip" && norms[0]["strip_left"] == true &&
                    norms[0]["strip_right"] == true && norms[1]["type"] == "Precompiled" &&
                    norms[2]["type"] == "Replace" && norms[2].obj("pattern")["Regex"] == " {2,}" &&
                    norms[2]["content"] == " ") { "Open-Decision normalizer differs from Strip/Precompiled/Replace." }
                PrecompiledCharsmap(decodeBase64(norms[1].string("precompiled_charsmap")))
            }
            UnigramFamily.GLINER_DECIDE -> {
                require(norms[0]["type"] == "Replace" && norms[0].obj("pattern")["Regex"] == "\\s{2,}|[\\n\\r\\t]" &&
                    norms[0]["content"] == " " && norms[1]["type"] == "NFC" && norms[2]["type"] == "Strip" &&
                    norms[2]["strip_left"] == false && norms[2]["strip_right"] == true) {
                    "GLiNER-Decide normalizer differs from Replace/NFC/Strip(right)."
                }
                null
            }
        }
        val pre = root.obj("pre_tokenizer")
        require(pre["type"] == "Sequence") { "$family pre-tokenizer must be a Sequence." }
        val stages = pre.array("pretokenizers").map(HostJson::obj)
        require(stages.size == 1 && stages[0]["type"] == "Metaspace" && stages[0]["replacement"] == "▁" &&
            stages[0]["prepend_scheme"] == "always" && stages[0]["split"] == true) {
            "$family requires Metaspace(prepend=always, split=true)."
        }
        return result
    }

    companion object {
        private val SPACES = Pattern.compile(" {2,}")
        private val REPEATED_WHITESPACE = Pattern.compile(
            "[\\x{09}-\\x{0d}\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}" +
                "\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}]{2,}|[\\n\\r\\t]",
        )

        private fun decodeBase64(text: String): ByteArray {
            require(text.isNotEmpty() && text.length % 4 == 0) { "Invalid Base64 charsmap length." }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val padding = text.takeLast(2).count { it == '=' }
            val output = ByteArray(text.length / 4 * 3 - padding)
            var target = 0
            for (at in text.indices step 4) {
                var bits = 0
                for (i in 0..3) {
                    val c = text[at + i]
                    val value = if (c == '=' && at + i >= text.length - padding) 0 else alphabet.indexOf(c)
                    require(value >= 0) { "Invalid Base64 character in charsmap." }
                    bits = (bits shl 6) or value
                }
                for (shift in listOf(16, 8, 0)) if (target < output.size) output[target++] = (bits ushr shift).toByte()
            }
            return output
        }
    }
}

/** SentencePiece darts-clone trie; the FIRST common-prefix match wins, not the longest. */
internal class PrecompiledCharsmap(blob: ByteArray) {
    private val array: IntArray
    private val normalized: ByteArray

    init {
        require(blob.size >= 8) { "Precompiled charsmap is truncated." }
        val bytes = readU32(blob, 0)
        require(bytes >= 4 && bytes % 4 == 0 && bytes.toLong() + 4 <= blob.size) {
            "Invalid precompiled charsmap trie size."
        }
        array = IntArray(bytes / 4) { readU32(blob, 4 + it * 4) }
        normalized = blob.copyOfRange(4 + bytes, blob.size)
    }

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
        graphemes.setText(text)
        var start = graphemes.first()
        var end = graphemes.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val bytes = grapheme.toByteArray(Charsets.UTF_8)
            val whole = if (bytes.size < 6) transform(bytes) else null
            if (whole != null) out.append(whole)
            else {
                var i = 0
                while (i < grapheme.length) {
                    val size = Character.charCount(grapheme.codePointAt(i))
                    val part = grapheme.substring(i, i + size)
                    out.append(transform(part.toByteArray(Charsets.UTF_8)) ?: part)
                    i += size
                }
            }
            start = end
            end = graphemes.next()
        }
        return out.toString()
    }

    private fun unit(index: Int): Int {
        require(index in array.indices) { "Precompiled charsmap points outside its trie ($index)." }
        return array[index]
    }

    private fun transform(key: ByteArray): String? {
        var node = offset(unit(0))
        for (byte in key) {
            val c = byte.toInt() and 255
            if (c == 0) break
            node = node xor c
            val unit = unit(node)
            if (unit and (Int.MIN_VALUE or 255) != c) return null
            node = node xor offset(unit)
            if ((unit ushr 8) and 1 == 1) {
                val index = unit(node) and Int.MAX_VALUE
                require(index in normalized.indices) { "Precompiled charsmap has an invalid replacement offset." }
                var stop = index
                while (stop < normalized.size && normalized[stop] != 0.toByte()) stop++
                require(stop < normalized.size) { "Precompiled charsmap has an unterminated replacement." }
                return String(normalized, index, stop - index, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)
    private fun readU32(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 255) or
        ((bytes[at + 1].toInt() and 255) shl 8) or ((bytes[at + 2].toInt() and 255) shl 16) or
        ((bytes[at + 3].toInt() and 255) shl 24)
}
