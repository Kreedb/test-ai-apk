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

import com.example.minimalapk.litert.CompiledModelRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/** Exact downloaded espeak IPA dictionary + OpenPhonemizer ForwardTransformer CPU OOV fallback. */
internal class MatchaG2P(modelDir: File, private val compiler: AudioCompiler) : AutoCloseable {
    companion object {
        const val MODEL = "dp_g2p_matcha_fp16.tflite"
        const val DICTIONARY = "g2p_dict.txt.gz"
    }

    private val chars = mutableMapOf<Char, Int>()
    private val phonemes: List<String>
    private val specials: Set<String>
    private val symbols = mutableMapOf<Char, Int>()
    private val repeats: Int
    private val maxFrames: Int
    private val start: Int
    private val end: Int
    private val dictionary: Map<String, String>
    private val file = modelFile(modelDir, MODEL)
    private var runner: CompiledModelRunner? = null

    init {
        fun json(name: String) = JSONObject(readAudioBytes(modelFile(modelDir, name), 64 * 1024).toString(Charsets.UTF_8))
        val meta = json("g2p_meta.json")
        val c2i = meta.getJSONObject("char2idx")
        for (key in c2i.keys()) {
            val id = c2i.getInt(key)
            require(id in 0..54) { "Unexpected G2P character vocabulary" }
            if (key.length == 1) chars[key[0]] = id
        }
        repeats = meta.getInt("char_repeats")
        maxFrames = meta.getInt("MAXT")
        start = meta.getInt("start")
        end = meta.getInt("end")
        require(repeats == 3 && maxFrames == 96 && start == 1 && end == 2 && meta.getInt("n_phonemes") == 64) {
            "G2P metadata does not match the pinned Matcha graph"
        }
        val i2p = meta.getJSONObject("idx2ph")
        phonemes = List(64) { i2p.getString(it.toString()).also { p -> require(p.length in 1..32) } }
        val special = meta.getJSONArray("special")
        require(special.length() in 1..16)
        specials = (0 until special.length()).map { special.getString(it) }.toSet()

        val config = json("config.json")
        require(config.getInt("n_vocab") == 178 && config.getInt("n_channels") == 192 &&
            config.getInt("n_feats") == 80 && config.getInt("MAX_TEXT") == 256 &&
            config.getInt("MAX_MEL") == 512 && config.getInt("hop") == 256 && config.getInt("sample_rate") == 22050) {
            "Matcha configuration does not match the pinned fixed-shape graphs"
        }
        val table = config.getJSONArray("symbols")
        require(table.length() == 178)
        for (i in 0 until table.length()) {
            val symbol = table.getString(i)
            require(symbol.length == 1)
            symbols[symbol[0]] = i // duplicate apostrophe deliberately follows upstream's last-id rule
        }

        val zipped = readAudioBytes(modelFile(modelDir, DICTIONARY), 4 * 1024 * 1024)
        val text = GZIPInputStream(ByteArrayInputStream(zipped)).use { readAudioStream(it, 16 * 1024 * 1024) }
            .toString(Charsets.UTF_8)
        val entries = HashMap<String, String>(300_000)
        var rows = 0
        for (line in text.lineSequence().filter { it.isNotBlank() }) {
            require(++rows <= 300_000 && line.length <= 2048) { "Matcha dictionary exceeds local bounds" }
            val tab = line.indexOf('\t')
            require(tab in 1..128 && tab < line.lastIndex) { "Malformed Matcha dictionary" }
            entries[line.substring(0, tab)] = line.substring(tab + 1)
        }
        require(entries.isNotEmpty()) { "Empty Matcha dictionary" }
        dictionary = entries
    }

    fun phonemize(text: String, control: AudioControl): IntArray =
        MatchaText.phonemize(text, symbols, control) { dictionary[it] ?: phonemizeWord(it, control) }

    private fun phonemizeWord(word: String, control: AudioControl): String {
        control.checkpoint()
        val characterIds = word.mapNotNull { chars[it] }
        require(characterIds.isNotEmpty() && characterIds.size * repeats + 2 <= maxFrames) {
            "Unknown word exceeds G2P's 31-character window: $word"
        }
        val input = FloatArray(maxFrames)
        var used = 0
        input[used++] = start.toFloat()
        characterIds.forEach { id -> repeat(repeats) { input[used++] = id.toFloat() } }
        input[used++] = end.toFloat()
        val graph = runner ?: compiler.model(file, cpuOnly = true).also { runner = it }
        control.checkpoint()
        graph.writeInput(0, input)
        graph.run()
        val logits = graph.readOutput(0)
        control.checkpoint()
        requireFinite(logits, "G2P logits", maxFrames * phonemes.size)
        return MatchaText.decode(logits, used, phonemes, specials).also {
            require(it.isNotBlank()) { "G2P produced no pronunciation for: $word" }
        }
    }

    override fun close() { runner?.close(); runner = null }
}
