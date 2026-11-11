package com.example.minimalapk.litert.text

import org.junit.Assert.*
import org.junit.Test

/** Small executable tokenizer.json fixtures, exercising production code without model downloads. */
class TokenizerAlgorithmTest {
    @Test fun rankedBpeInvalidatesStalePairsAndKeepsLeftmostTies() {
        val vocabulary = mapOf("a" to 0, "b" to 1, "c" to 2, "ab" to 3, "bc" to 4, "abc" to 5,
            "aa" to 6, "aaa" to 7)
        val bpe = RankedBpe(vocabulary, listOf(listOf("b", "c"), "a bc", "a b", "a a", "aa a"))
        val actual = mutableListOf<Int>()
        bpe.encode(intArrayOf(0, 1, 2), actual)
        assertEquals(listOf(5), actual) // bc first, stale ab must NOT be merged.
        actual.clear()
        bpe.encode(intArrayOf(0, 0, 0), actual)
        assertEquals(listOf(7), actual) // leftmost aa, then aaa.
        assertThrows(IllegalArgumentException::class.java) { RankedBpe(vocabulary, listOf("a missing")) }
    }

    @Test fun addedTokensMatchLongestWithUnicodeWhitespaceAndOrdinaryTextIsNotRenormalized() {
        val tokens = AddedTokens()
        tokens.insert("<x>", 7)
        tokens.insert("<x>!", 8, leftStrip = true, rightStrip = true)
        val pieces = mutableListOf<String>()
        tokens.split("a\u00a0<x>!\u3000b<x>", { pieces.add(it) }, { pieces.add("#$it") })
        assertEquals(listOf("a", "#8", "b", "#7"), pieces)
    }

    @Test fun gemmaMetaspaceAndByteFallbackDoNotApplyNfc() {
        val tokenizer = tinyGemma()
        assertArrayEquals(intArrayOf(9), tokenizer.encode("ab"))
        assertArrayEquals(intArrayOf(9, 9), tokenizer.encode("ab ab"))
        assertArrayEquals(intArrayOf(9, 4, 9), tokenizer.encode("ab\u00a0<mask>ab"))
        assertArrayEquals(intArrayOf(5, 11, 12, 13, 14), tokenizer.encode("😀"))
        // Decomposed and precomposed characters have intentionally different tokens in Gemma.
        assertArrayEquals(intArrayOf(5, 10, 15, 16), tokenizer.encode("e\u0301"))
        assertArrayEquals(intArrayOf(5, 17), tokenizer.encode("é"))
    }

    @Test fun byteLevelNfcAddedTokenPassesAndPrefixSpaceMatchPipeline() {
        val tokenizer = tinyGliclass()
        val byteCharacters = GliclassTokenizer.byteCharacters()
        fun id(c: Char): Int = byteCharacters.indexOf(c)
        val space = id('Ġ')
        assertArrayEquals(intArrayOf(256, space, 195, 169, 257), tokenizer.encode("e\u0301"))
        assertArrayEquals(tokenizer.encode("e\u0301"), tokenizer.encode("é"))
        // [MASK] consumes NBSP on the LEFT, then each ordinary segment gets a prefix space.
        assertArrayEquals(intArrayOf(256, space, 97, 259, space, 98, 257), tokenizer.encode("a\u00a0[MASK]b"))
        assertArrayEquals(intArrayOf(256, space, 97, 260, space, 98, 257), tokenizer.encode("a  b"))
        assertArrayEquals(intArrayOf(256, 257), tokenizer.encode(""))
    }

    @Test fun unigramUsesGlobalScoreAndFusesUnknownCodePointsWithNfcButNotNfkc() {
        val tokenizer = tinyUnigram()
        assertArrayEquals(intArrayOf(7, 8), tokenizer.encode("ab")) // ▁a + b beats ▁ab.
        assertArrayEquals(intArrayOf(6, 3), tokenizer.encode("xy")) // unknown run is ONE id.
        assertArrayEquals(intArrayOf(10), tokenizer.encode("e\u0301"))
        assertArrayEquals(intArrayOf(6, 3), tokenizer.encode("Ａ")) // NFC must not width-fold.
        assertArrayEquals(intArrayOf(7, 6, 3, 11), tokenizer.encode("a [UNK] b"))
        assertArrayEquals(intArrayOf(7, 11), tokenizer.encode("a\t b"))
        assertArrayEquals(intArrayOf(6, 3), tokenizer.encode("𐀀𐀁")) // supplementary scalars fuse.
    }

    @Test fun layaTruncatesOnlyAsItsContractWhileJuliaRejectsTheSameOverflow() {
        val tokenizer = tinyGemma()
        // Host templates need a richer vocab than this fixture; byte-level fake token ids are
        // avoided by supplying valid fallback symbols for every ASCII character in the fixture.
        val encode = tokenizer::encode
        val q = LayaQuestion.choice("ab", linkedMapOf("a" to "ab", "b" to "ab"))
        // Exercise the sequence algorithms with a deterministic TOKENIZER, not model-output mocks.
        val laya = LayaPromptBuilder(encode).build("ab ".repeat(300), q)
        assertEquals(256, laya.ids.size)
        assertTrue(laya.retainedStateTokens < laya.originalStateTokens)
        assertEquals(2, laya.markers.size)
        assertTrue(laya.markers.all { laya.ids[it] == 4 })
        val request = TextDecisionRequest("ab ".repeat(600), "ab", listOf("a" to "ab", "b" to "ab"))
        val failure = assertThrows(IllegalArgumentException::class.java) { JuliaSequenceBuilder(encode).build(request) }
        assertTrue(failure.message!!.contains("Nothing was truncated"))
        assertThrows(IllegalArgumentException::class.java) {
            JuliaSequenceBuilder(encode).build(request.copy(text = "ab", question = "<mask>"))
        }
    }

    @Test fun precompiledNormalizerRejectsInvalidOffsetsRatherThanCrashingWithIndexError() {
        assertThrows(IllegalArgumentException::class.java) { PrecompiledCharsmap(byteArrayOf(4, 0)) }
        val charsmap = PrecompiledCharsmap(byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { charsmap.normalize("a") }
    }

    private fun token(content: String, id: Int, normalized: Boolean = false, lstrip: Boolean = false): JsonObject =
        mapOf("content" to content, "id" to id, "normalized" to normalized,
            "lstrip" to lstrip, "rstrip" to false, "single_word" to false)

    private fun tinyGemma(): GemmaTokenizer {
        val vocab = linkedMapOf("<pad>" to 0, "<eos>" to 1, "<bos>" to 2, "<unk>" to 3, "<mask>" to 4,
            "▁" to 5, "a" to 6, "b" to 7, "▁a" to 8, "▁ab" to 9, "e" to 10,
            "<0xF0>" to 11, "<0x9F>" to 12, "<0x98>" to 13, "<0x80>" to 14,
            "<0xCC>" to 15, "<0x81>" to 16, "é" to 17)
        for (byte in 0..127) vocab.putIfAbsent("<0x${byte.toString(16).uppercase().padStart(2, '0')}>", vocab.size)
        return GemmaTokenizer(mapOf(
            "model" to mapOf("type" to "BPE", "byte_fallback" to true, "dropout" to null,
                "continuing_subword_prefix" to null, "end_of_word_suffix" to null, "ignore_merges" to false,
                "unk_token" to "<unk>", "vocab" to vocab, "merges" to listOf(listOf("▁", "a"), listOf("▁a", "b"))),
            "normalizer" to mapOf("type" to "Replace", "content" to "▁", "pattern" to mapOf("String" to " ")),
            "pre_tokenizer" to mapOf("type" to "Metaspace", "replacement" to "▁", "prepend_scheme" to "always", "split" to true),
            "added_tokens" to listOf(token("<mask>", 4, lstrip = true)),
        ), vocab.size)
    }

    private fun tinyGliclass(): GliclassTokenizer {
        val vocab = GliclassTokenizer.byteCharacters().mapIndexed { index, c -> c.toString() to index }.toMap()
        return GliclassTokenizer(mapOf(
            "model" to mapOf("type" to "BPE", "vocab" to vocab, "merges" to emptyList<Any>()),
            "normalizer" to mapOf("type" to "NFC"),
            "pre_tokenizer" to mapOf("type" to "ByteLevel", "add_prefix_space" to true, "use_regex" to true),
            "added_tokens" to listOf(token("[CLS]", 256), token("[SEP]", 257), token("[PAD]", 258),
                token("[MASK]", 259, lstrip = true), token("  ", 260, normalized = true)),
            "post_processor" to mapOf("type" to "TemplateProcessing", "single" to listOf(
                mapOf("SpecialToken" to mapOf("id" to "[CLS]")), mapOf("Sequence" to mapOf("id" to "A")),
                mapOf("SpecialToken" to mapOf("id" to "[SEP]"))), "special_tokens" to mapOf(
                "[CLS]" to mapOf("ids" to listOf(256)), "[SEP]" to mapOf("ids" to listOf(257)))),
        ))
    }

    private fun tinyUnigram(): UnigramTokenizer = UnigramTokenizer(mapOf(
        "model" to mapOf("type" to "Unigram", "unk_id" to 3, "vocab" to listOf(
            listOf("[PAD]", 0.0), listOf("[CLS]", 0.0), listOf("[SEP]", 0.0), listOf("[UNK]", 0.0),
            listOf("a", -2.0), listOf("z", -2.0), listOf("▁", -1.0), listOf("▁a", -.1),
            listOf("b", -.2), listOf("▁ab", -3.0), listOf("▁é", -.3), listOf("▁b", -.4))),
        "normalizer" to mapOf("type" to "Sequence", "normalizers" to listOf(
            mapOf("type" to "Replace", "pattern" to mapOf("Regex" to "\\s{2,}|[\\n\\r\\t]"), "content" to " "),
            mapOf("type" to "NFC"), mapOf("type" to "Strip", "strip_left" to false, "strip_right" to true))),
        "pre_tokenizer" to mapOf("type" to "Sequence", "pretokenizers" to listOf(
            mapOf("type" to "Metaspace", "replacement" to "▁", "prepend_scheme" to "always", "split" to true))),
        "added_tokens" to listOf(token("[PAD]", 0), token("[UNK]", 3, normalized = true)),
    ), UnigramFamily.GLINER_DECIDE, 12)
}
