/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; https://www.apache.org/licenses/LICENSE-2.0
 * Fixtures from litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert/text_to_speech/ios/Tests/MatchaCoreTests/Resources/text_fixtures.json
 * and Model Zoo models/matcha/MatchaMathTest.kt.
 */
package com.example.minimalapk.litert.audio

import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class MatchaDecodeTest {
    // Pinned Matcha-TTS config.json @143eb4236e8c81ea6849ede31f7f088ea2393cfa.
    private val symbols = ("_;:,.!?¡¿—…\"«»“” ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
        "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ")
        .mapIndexed { index, c -> c to index }.toMap()
    private val dictionary = mapOf("the" to "ðə", "rain" to "ɹˈeɪn", "is" to "ɪz", "soft" to "sˈɔft",
        "today" to "tədˈeɪ", "count" to "kˈaʊnt", "four" to "fˈoːɹ", "thousand" to "θˈaʊzənd",
        "ninety" to "nˈaɪnti", "and" to "ænd", "measure" to "mˈɛʒɚ", "one" to "wˌʌn", "two" to "tˈuː",
        "hundred" to "hˈʌndɹɪd", "thirty" to "θˈɜːɾi", "point" to "pˈɔɪnt", "five" to "fˈaɪv")

    @Test fun pinnedPlainTextIpaMapsToExactTrainingSymbolIds() {
        assertArrayEquals(intArrayOf(81,83,16,123,156,47,102,56,16,102,68,16,61,156,76,48,62,16,62,83,46,156,47,102,4),
            phonemize("The rain is soft today."))
    }

    @Test fun pinnedAcronymFixtureIsSpelledOutWithoutDictionaryOrLocaleDependency() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val ids = MatchaText.phonemize("GPU CPU TTS API FAQ", symbols, AudioControl()) { error("No word lookup expected") }
            assertArrayEquals(intArrayOf(46,147,156,51,158,58,156,51,158,52,156,63,158,16,61,156,51,158,58,156,51,158,52,156,63,158,16,
                62,156,51,158,62,156,51,158,156,86,61,16,156,47,102,58,156,51,158,156,43,102,16,156,86,48,156,47,102,53,52,156,63,158), ids)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun pinnedGroupedNumberFixturePreservesPronunciationAndPunctuation() {
        val expected = "ðə kˈaʊnt ɪz fˈoːɹ θˈaʊzənd nˈaɪnti ænd ðə mˈɛʒɚ ɪz wˌʌn θˈaʊzənd tˈuː hˈʌndɹɪd θˈɜːɾi fˈoːɹ pˈɔɪnt fˈaɪv."
        assertArrayEquals(expected.map { symbols.getValue(it) }.toIntArray(),
            phonemize("The count is 4090 and the measure is 1,234.5."))
        assertEquals(listOf("one", "thousand", "two", "hundred", "thirty", "four", "point", "five"), MatchaText.numberWords("1,234.5"))
        assertEquals(listOf("zero", "point", "zero", "zero", "seven"), MatchaText.numberWords("0.007"))
        assertEquals("minus", MatchaText.integerWords(Long.MIN_VALUE).first())
        assertEquals(20, MatchaText.integerWords(Long.MIN_VALUE).size)
    }

    @Test fun neuralG2pRepeatsSpecialsAndHyphensDecodeLikeUpstream() {
        val vocabulary = listOf("_", "<en_us>", "<end>", "a", "b-", "ˈ")
        val ids = intArrayOf(1, 5, 5, 3, 3, 0, 3, 4, 4, 2)
        val logits = FloatArray(ids.size * vocabulary.size) { -4f }
        ids.forEachIndexed { t, id -> logits[t * vocabulary.size + id] = 4f }
        assertEquals("ˈaab", MatchaText.decode(logits, ids.size, vocabulary, setOf("_", "<en_us>", "<end>")))
    }

    @Test fun blankInterspersionAndFractionalDurationExpansionMatchMatchaProtocol() {
        val (ids, mask) = MatchaSynthesizer.intersperse(intArrayOf(81, 83))
        assertArrayEquals(intArrayOf(0, 81, 0, 83, 0, 0), ids.copyOf(6))
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f), mask.copyOf(6), 0f)
        val mu = FloatArray(80 * 256) { (it % 256 + 10 * (it / 256)).toFloat() }
        val durations = FloatArray(256)
        durations[0] = ln(1.1f); durations[1] = ln(2.1f)
        val two = FloatArray(256) { if (it < 2) 1f else 0f }
        val expanded = MatchaSynthesizer.expandDurations(mu, durations, two)
        // ceil(1.1)*.95=1.9; ceil(2.1)*.95=2.85; floor(total)=4, not rounded durations.
        assertEquals(4, expanded.frames)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f, 0f), expanded.mean.copyOf(5), 0f)
        assertArrayEquals(floatArrayOf(10f, 10f, 11f, 11f), expanded.mean.copyOfRange(512, 516), 0f)
        assertTrue(expanded.mask.drop(4).all { it == 0f })
    }

    @Test fun cfmEulerMasksPaddingAndUsesSineThenCosineTimeEmbedding() {
        val zero = MatchaSynthesizer.sinPosEmb(0f)
        assertArrayEquals(FloatArray(80), zero.copyOf(80), 0f)
        assertArrayEquals(FloatArray(80) { 1f }, zero.copyOfRange(80, 160), 0f)
        val embedding = MatchaSynthesizer.sinPosEmb(0.001f)
        assertEquals(0.84147096f, embedding[0], 1e-6f)
        assertEquals(0.5403023f, embedding[80], 1e-6f)
        assertEquals(0.0001f, embedding[79], 1e-7f)
        val state = FloatArray(80 * 512) { 1f }
        MatchaSynthesizer.eulerStep(state, FloatArray(state.size) { 2f }, 0.1f, 3)
        assertArrayEquals(floatArrayOf(1.2f, 1.2f, 1.2f, 0f), state.copyOf(4), 0f)
        assertEquals(1.2f, state[512], 0f)
    }

    @Test fun overlengthTextDurationsInvalidFloatsAndCancellationFailBeforeUnboundedWork() {
        assertThrows(IllegalArgumentException::class.java) { phonemize("中文") }
        assertThrows(IllegalArgumentException::class.java) { phonemize("!!!") }
        assertThrows(IllegalArgumentException::class.java) { phonemize("rain ".repeat(100)) }
        assertThrows(IllegalArgumentException::class.java) { MatchaSynthesizer.intersperse(IntArray(128) { 1 }) }
        assertThrows(IllegalArgumentException::class.java) { MatchaSynthesizer.intersperse(intArrayOf(178)) }
        val mean = FloatArray(80 * 256)
        assertThrows(IllegalArgumentException::class.java) {
            MatchaSynthesizer.expandDurations(mean, FloatArray(256) { 8f }, FloatArray(256) { 1f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchaSynthesizer.expandDurations(mean, FloatArray(256) { Float.NaN }, FloatArray(256) { 1f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchaSynthesizer.expandDurations(mean, FloatArray(256) { -1000f }, FloatArray(256) { 1f })
        }
        assertThrows(CancellationException::class.java) {
            MatchaText.phonemize("rain", symbols, AudioControl({ true })) { error("Must not look up after cancel") }
        }
    }

    private fun phonemize(text: String): IntArray = MatchaText.phonemize(text, symbols, AudioControl()) { dictionary.getValue(it) }
}
