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

// Ports the ranked merge and added-token algorithms of upstream typed_decisions:
// LayaTokenizer.kt, JuliaTokenizer.kt and GliclassTokenizer.kt at
// google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8.
// Their pinned litert-community sources and retained attribution are in ATTRIBUTION.md.
// Changes: share only identical algorithms; keep Gemma Metaspace and ModernBERT ByteLevel
// normalization distinct, use HostJson, and validate malformed tokenizer configurations.
package com.example.minimalapk.litert.text

import java.io.File
import java.text.Normalizer
import java.util.PriorityQueue
import java.util.regex.Pattern

internal fun unicodeWhitespace(cp: Int): Boolean = cp in 0x09..0x0d || cp == 0x20 || cp == 0x85 ||
    cp == 0xa0 || cp == 0x1680 || cp in 0x2000..0x200a || cp == 0x2028 || cp == 0x2029 ||
    cp == 0x202f || cp == 0x205f || cp == 0x3000

/** Leftmost-longest raw/normalized added-token matching, including Unicode lstrip/rstrip. */
internal class AddedTokens {
    private data class Token(val id: Int, val leftStrip: Boolean, val rightStrip: Boolean)
    private class Node {
        val children = HashMap<Char, Node>()
        var token: Token? = null
    }
    private val root = Node()

    fun insert(content: String, id: Int, leftStrip: Boolean = false, rightStrip: Boolean = false) {
        require(content.isNotEmpty() && id >= 0) { "Empty added token or negative token id." }
        var node = root
        content.forEach { node = node.children.getOrPut(it) { Node() } }
        require(node.token == null) { "Duplicate added token '$content'." }
        node.token = Token(id, leftStrip, rightStrip)
    }

    fun split(text: String, onText: (String) -> Unit, onToken: (Int) -> Unit) {
        var unprocessed = 0
        var position = 0
        while (position < text.length) {
            var node = root
            var end = position
            var matched: Token? = null
            var matchedEnd = position
            while (end < text.length) {
                node = node.children[text[end]] ?: break
                end++
                node.token?.let { matched = it; matchedEnd = end }
            }
            val token = matched
            if (token == null) { position++; continue }
            var begin = position
            if (token.leftStrip) {
                while (begin > unprocessed && unicodeWhitespace(text.codePointBefore(begin))) {
                    begin -= Character.charCount(text.codePointBefore(begin))
                }
            }
            if (begin > unprocessed) onText(text.substring(unprocessed, begin))
            onToken(token.id)
            if (token.rightStrip) {
                while (matchedEnd < text.length && unicodeWhitespace(text.codePointAt(matchedEnd))) {
                    matchedEnd += Character.charCount(text.codePointAt(matchedEnd))
                }
            }
            position = matchedEnd
            unprocessed = matchedEnd
        }
        if (unprocessed < text.length) onText(text.substring(unprocessed))
    }
}

/** Hugging Face Word::merge_all: rank first, leftmost on ties, invalidate stale neighbours. */
internal class RankedBpe(vocabulary: Map<String, Int>, rawMerges: List<Any?>) {
    private data class Merge(val rank: Int, val id: Int)
    private data class Candidate(
        val rank: Int, val left: Int, val right: Int, val leftId: Int, val rightId: Int, val id: Int,
    )
    private val merges = HashMap<Long, Merge>(rawMerges.size * 4 / 3 + 1)

    init {
        rawMerges.forEachIndexed { rank, entry ->
            val pair = when (entry) {
                is List<*> -> {
                    require(entry.size == 2 && entry.all { it is String }) { "BPE merge $rank is not a pair." }
                    (entry[0] as String) to (entry[1] as String)
                }
                is String -> {
                    val space = entry.indexOf(' ', 1)
                    require(space > 0) { "BPE merge $rank is not a pair." }
                    entry.substring(0, space) to entry.substring(space + 1)
                }
                else -> throw IllegalArgumentException("BPE merge $rank is not a pair.")
            }
            val left = requireNotNull(vocabulary[pair.first]) { "BPE merge $rank has unknown left token." }
            val right = requireNotNull(vocabulary[pair.second]) { "BPE merge $rank has unknown right token." }
            val merged = requireNotNull(vocabulary[pair.first + pair.second]) { "BPE merge $rank has no result token." }
            merges[key(left, right)] = Merge(rank, merged)
        }
    }

    fun encode(symbols: IntArray, output: MutableList<Int>) {
        if (symbols.size < 2) { output.addAll(symbols.asList()); return }
        val ids = symbols.copyOf()
        val previous = IntArray(ids.size) { it - 1 }
        val next = IntArray(ids.size) { if (it + 1 < ids.size) it + 1 else -1 }
        val alive = BooleanArray(ids.size) { true }
        val queue = PriorityQueue(compareBy<Candidate> { it.rank }.thenBy { it.left })
        fun offer(left: Int) {
            if (left < 0 || !alive[left]) return
            val right = next[left]
            if (right < 0) return
            val merge = merges[key(ids[left], ids[right])] ?: return
            queue.add(Candidate(merge.rank, left, right, ids[left], ids[right], merge.id))
        }
        for (index in 0 until ids.size - 1) offer(index)
        while (queue.isNotEmpty()) {
            val c = queue.remove()
            if (!alive[c.left] || !alive[c.right] || next[c.left] != c.right ||
                ids[c.left] != c.leftId || ids[c.right] != c.rightId) continue
            ids[c.left] = c.id
            alive[c.right] = false
            next[c.left] = next[c.right]
            if (next[c.right] >= 0) previous[next[c.right]] = c.left
            offer(previous[c.left])
            offer(c.left)
        }
        ids.indices.forEach { if (alive[it]) output.add(ids[it]) }
    }

    private fun key(left: Int, right: Int): Long =
        (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)
}

/**
 * Laya multilingual and Julia-1 share the EXACT tokenizer.json SHA-256
 * 609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f.
 * Their prompt, graph, embedding width, limits and decoder remain separate.
 */
internal class GemmaTokenizer internal constructor(root: JsonObject, expectedVocabularySize: Int) {
    constructor(file: File) : this(HostJson.obj(HostJson.parse(file)), 256_000)

    private val vocabulary: Map<String, Int>
    private val bpe: RankedBpe
    private val added = AddedTokens()
    private val byteIds = IntArray(256)

    init {
        val model = root.obj("model")
        require(model["type"] == "BPE" && model["byte_fallback"] == true &&
            model["dropout"] == null && model["continuing_subword_prefix"] == null &&
            model["end_of_word_suffix"] == null && model["ignore_merges"] == false &&
            model["unk_token"] == "<unk>") { "Expected the pinned Gemma byte-fallback BPE tokenizer." }
        val normalizer = root.obj("normalizer")
        require(normalizer["type"] == "Replace" && normalizer["content"] == "▁" &&
            normalizer.obj("pattern")["String"] == " ") { "Gemma tokenizer must replace U+0020 with Metaspace." }
        val pre = root.obj("pre_tokenizer")
        require(pre["type"] == "Metaspace" && pre["replacement"] == "▁" &&
            pre["prepend_scheme"] == "always" && pre["split"] == true) { "Unsupported Gemma Metaspace pipeline." }
        vocabulary = model.obj("vocab").mapValues { (token, value) -> value.integer("Vocabulary id '$token'") }
        require(vocabulary.size == expectedVocabularySize && vocabulary.values.toSet().size == vocabulary.size &&
            vocabulary.values.all { it < expectedVocabularySize }) { "Invalid Gemma vocabulary size/ids." }
        require(listOf("<pad>", "<eos>", "<bos>", "<unk>", "<mask>").map { vocabulary[it] } ==
            listOf(0, 1, 2, 3, 4)) { "Gemma special token ids must be PAD=0 EOS=1 BOS=2 UNK=3 MASK=4." }
        for (i in byteIds.indices) {
            byteIds[i] = vocabulary["<0x${i.toString(16).uppercase().padStart(2, '0')}>"] ?: -1
        }
        bpe = RankedBpe(vocabulary, model.array("merges"))
        for (value in root.array("added_tokens")) {
            val entry = HostJson.obj(value)
            require(entry["normalized"] == false && entry["single_word"] == false) { "Unsupported Gemma added-token policy." }
            val content = entry.string("content")
            val id = entry["id"].integer("Added-token id")
            require(vocabulary[content] == id) { "Added token '$content' does not match Gemma vocabulary." }
            added.insert(content, id, entry.boolean("lstrip"), entry.boolean("rstrip"))
        }
    }

    fun encode(text: String): IntArray {
        val result = ArrayList<Int>()
        added.split(text, { ordinary(it, result) }, { result.add(it) })
        return result.toIntArray()
    }

    private fun ordinary(text: String, result: MutableList<Int>) {
        var normalized = text.replace(' ', '▁')
        if (!normalized.startsWith('▁')) normalized = "▁$normalized"
        var start = 0
        for (index in 1 until normalized.length) if (normalized[index] == '▁') {
            piece(normalized.substring(start, index), result)
            start = index
        }
        piece(normalized.substring(start), result)
    }

    private fun piece(text: String, output: MutableList<Int>) {
        val symbols = ArrayList<Int>()
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val scalar = String(Character.toChars(cp))
            val id = vocabulary[scalar]
            if (id != null) symbols.add(id)
            else for (byte in scalar.toByteArray(Charsets.UTF_8)) {
                val fallback = byteIds[byte.toInt() and 255]
                require(fallback >= 0) { "Missing byte fallback for U+${cp.toString(16)}." }
                symbols.add(fallback)
            }
            offset += Character.charCount(cp)
        }
        bpe.encode(symbols.toIntArray(), output)
    }
}

/** GLiClass-Edge's NFC + ByteLevel BPE, including the two added-token matching passes. */
internal class GliclassTokenizer(root: JsonObject) {
    constructor(file: File) : this(HostJson.obj(HostJson.parse(file)))

    private val vocabulary: Map<String, Int>
    private val bpe: RankedBpe
    private val raw = AddedTokens()
    private val normalized = AddedTokens()
    private val addedIds = HashMap<String, Int>()
    private val byteIds: IntArray
    val clsId: Int
    val sepId: Int
    val padId: Int
    val vocabularySize: Int

    init {
        val model = root.obj("model")
        require(model["type"] == "BPE" && model["dropout"] == null && model["unk_token"] == null &&
            model["continuing_subword_prefix"] == null && model["end_of_word_suffix"] == null &&
            model["byte_fallback"] != true && model["ignore_merges"] != true) {
            "Expected the GLiClass-Edge byte-level BPE tokenizer."
        }
        require(root.obj("normalizer")["type"] == "NFC") { "GLiClass normalizer must be NFC." }
        val pre = root.obj("pre_tokenizer")
        require(pre["type"] == "ByteLevel" && pre["add_prefix_space"] == true && pre["use_regex"] == true) {
            "GLiClass requires ByteLevel(add_prefix_space=true, use_regex=true)."
        }
        vocabulary = model.obj("vocab").mapValues { (token, value) -> value.integer("Vocabulary id '$token'") }
        require(vocabulary.values.toSet().size == vocabulary.size) { "Duplicate GLiClass vocabulary ids." }
        val characters = byteCharacters()
        byteIds = IntArray(256) { vocabulary[characters[it].toString()] ?: -1 }
        bpe = RankedBpe(vocabulary, model.array("merges"))
        var size = (vocabulary.values.maxOrNull() ?: -1) + 1
        for (value in root.array("added_tokens")) {
            val entry = HostJson.obj(value)
            require(entry["single_word"] == false && entry["rstrip"] == false) { "Unsupported GLiClass added-token policy." }
            val content = entry.string("content")
            val id = entry["id"].integer("Added-token id")
            if (entry.boolean("normalized")) normalized.insert(nfc(content), id, entry.boolean("lstrip"))
            else raw.insert(content, id, entry.boolean("lstrip"))
            addedIds[content] = id
            size = maxOf(size, id + 1)
        }
        vocabularySize = size
        clsId = requireNotNull(addedIds["[CLS]"]) { "Missing GLiClass [CLS]." }
        sepId = requireNotNull(addedIds["[SEP]"]) { "Missing GLiClass [SEP]." }
        padId = requireNotNull(addedIds["[PAD]"]) { "Missing GLiClass [PAD]." }
        val post = root.obj("post_processor")
        require(post["type"] == "TemplateProcessing") { "GLiClass requires TemplateProcessing." }
        val pieces = post.array("single").map {
            val item = HostJson.obj(it)
            if ("SpecialToken" in item) item.obj("SpecialToken").string("id")
            else "$" + item.obj("Sequence").string("id")
        }
        require(pieces == listOf("[CLS]", "\$A", "[SEP]")) { "GLiClass template must be [CLS] A [SEP]." }
        val special = post.obj("special_tokens")
        require(special.obj("[CLS]").array("ids").single().integer("CLS id") == clsId &&
            special.obj("[SEP]").array("ids").single().integer("SEP id") == sepId) { "GLiClass template ids disagree." }
    }

    fun tokenId(token: String): Int? = addedIds[token] ?: vocabulary[token]

    fun encode(text: String): IntArray {
        val ids = arrayListOf(clsId)
        raw.split(text, { segment ->
            normalized.split(nfc(segment), { byteLevel(it, ids) }, { ids.add(it) })
        }, { ids.add(it) })
        ids.add(sepId)
        return ids.toIntArray()
    }

    private fun byteLevel(segment: String, output: MutableList<Int>) {
        val text = if (segment.startsWith(' ')) segment else " $segment"
        val matcher = SPLIT.matcher(text)
        var last = 0
        while (matcher.find()) {
            if (matcher.start() > last) piece(text.substring(last, matcher.start()), output)
            piece(matcher.group(), output)
            last = matcher.end()
        }
        if (last < text.length) piece(text.substring(last), output)
    }

    private fun piece(text: String, output: MutableList<Int>) {
        val symbols = text.toByteArray(Charsets.UTF_8).map { byteIds[it.toInt() and 255] }
        // The published tokenizer has every valid UTF-8 byte; a missing one is a corrupt resource.
        require(symbols.all { it >= 0 }) { "GLiClass vocabulary lacks a required UTF-8 byte token." }
        bpe.encode(symbols.toIntArray(), output)
    }

    companion object {
        private const val SPACE = "\\x{09}-\\x{0d}\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}" +
            "\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}"
        private val SPLIT = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^$SPACE\\p{L}\\p{N}]+" +
                "|[$SPACE]+(?![^$SPACE])|[$SPACE]+",
        )
        private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        internal fun byteCharacters(): CharArray {
            val printable = (0x21..0x7e) + (0xa1..0xac) + (0xae..0xff)
            var extra = 0
            return CharArray(256) { if (it in printable) it.toChar() else (256 + extra++).toChar() }
        }
    }
}
