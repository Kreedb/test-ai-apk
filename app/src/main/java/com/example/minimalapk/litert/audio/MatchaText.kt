/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/matcha/MatchaG2P.kt.
 */
package com.example.minimalapk.litert.audio

import java.util.Locale

/** Pure host G2P normalization, with the espeak dictionary/neural lookup supplied by MatchaG2P. */
internal object MatchaText {
    private val token = Regex("[A-Z]{2,}|\\d[\\d,]*(?:\\.\\d+)?|[A-Za-z']+|[.,!?;:—…\"]")
    private val acronym = Regex("[A-Z]{2,}")
    private val word = Regex("[A-Za-z']+")
    private val letter = arrayOf(
        "ˈeɪ", "bˈiː", "sˈiː", "dˈiː", "ˈiː", "ˈɛf", "dʒˈiː", "ˈeɪtʃ", "ˈaɪ", "dʒˈeɪ",
        "kˈeɪ", "ˈɛl", "ˈɛm", "ˈɛn", "ˈoʊ", "pˈiː", "kjˈuː", "ˈɑːɹ", "ˈɛs", "tˈiː",
        "jˈuː", "vˈiː", "dˈʌbəljˌuː", "ˈɛks", "wˈaɪ", "zˈiː",
    )
    private val ones = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = arrayOf("", "thousand", "million", "billion", "trillion")

    fun numberWords(raw: String): List<String> {
        val clean = raw.replace(",", "")
        require(clean.length in 1..128 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(clean)) { "Invalid spoken number" }
        val parts = clean.split('.', limit = 2)
        val integer = parts[0].toLongOrNull()?.let(::integerWords) ?: parts[0].map { ones[it - '0'] }
        return if (parts.size == 1) integer else integer + "point" + parts[1].map { ones[it - '0'] }
    }

    fun integerWords(value: Long): List<String> {
        if (value == 0L) return listOf("zero")
        // Avoid negating MIN_VALUE (which overflows and recursively calls itself).
        if (value == Long.MIN_VALUE) return listOf("minus") + value.toString().drop(1).map { ones[it - '0'] }
        if (value < 0L) return listOf("minus") + integerWords(-value)
        var remaining = value
        val groups = mutableListOf<Int>()
        while (remaining > 0) { groups += (remaining % 1000).toInt(); remaining /= 1000 }
        if (groups.size > scales.size) return value.toString().map { ones[it - '0'] }
        val result = mutableListOf<String>()
        for (i in groups.indices.reversed()) {
            var n = groups[i]
            if (n == 0) continue
            if (n >= 100) { result += ones[n / 100]; result += "hundred"; n %= 100 }
            if (n >= 20) { result += tens[n / 10]; n %= 10 }
            if (n > 0) result += ones[n]
            if (i > 0) result += scales[i]
        }
        return result
    }

    fun phonemize(
        text: String,
        symbols: Map<Char, Int>,
        control: AudioControl,
        lookup: (String) -> String,
    ): IntArray {
        require(text.isNotBlank() && text.length <= AudioLimits.MAX_TEXT_CHARS) { "Enter a short English phrase" }
        val normalized = text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        require(normalized.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it.isWhitespace() ||
            it in "'.,!?;:—…\"-()" }) { "Matcha-LJSpeech supports English letters, numbers and punctuation only" }
        val ipa = StringBuilder()
        fun add(pronunciation: String) {
            if (pronunciation.isEmpty()) return
            if (ipa.isNotEmpty()) ipa.append(' ')
            ipa.append(pronunciation)
            require(ipa.length <= 2048) { "Phrase is too long for Matcha" }
        }
        for (match in token.findAll(normalized)) {
            control.checkpoint()
            val part = match.value
            when {
                acronym.matches(part) -> add(part.map { letter[it - 'A'] }.joinToString(""))
                part[0] in '0'..'9' -> numberWords(part).forEach { control.checkpoint(); add(lookup(it)) }
                word.matches(part) -> add(lookup(part.lowercase(Locale.ROOT)))
                else -> ipa.append(part)
            }
        }
        val ids = ipa.mapNotNull { symbols[it] }.toIntArray()
        require(ids.any { it > 16 }) { "Text contains no supported English phonemes" }
        require(ids.size * 2 + 1 <= MatchaSynthesizer.MAX_TEXT) { "Phrase exceeds 127 phoneme symbols; split it into shorter phrases" }
        return ids
    }

    /** DeepPhonemizer repeats collapse across time; specials/blanks reset repetition. */
    fun decode(logits: FloatArray, frames: Int, phonemes: List<String>, specials: Set<String>): String {
        require(phonemes.size in 1..256 && frames in 1..256 && logits.size >= frames * phonemes.size)
        requireFinite(logits, "G2P logits")
        val result = StringBuilder()
        var previous = -1
        for (t in 0 until frames) {
            var best = 0
            val base = t * phonemes.size
            for (p in 1 until phonemes.size) if (logits[base + p] > logits[base + best]) best = p
            if (best != previous && best != 0 && phonemes[best] !in specials) result.append(phonemes[best].replace("-", ""))
            previous = best
        }
        return result.toString()
    }
}
