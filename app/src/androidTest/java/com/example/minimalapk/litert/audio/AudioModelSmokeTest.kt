/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; https://www.apache.org/licenses/LICENSE-2.0
 * Fixed speech fixture: google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert/flutter/assets/selftest/what_time_is_it.wav (Inflect-generated "What time is it?").
 * Models are downloaded by the host; tests never download or bundle weights.
 */
package com.example.minimalapk.litert.audio

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in actual audio correctness smoke, not tensor-shape-only tests.
 * Instrumentation args:
 *   -e audioModels /data/local/tmp/litert-assets
 *   -e audioFixture /data/local/tmp/what_time_is_it.wav
 *   -e audioBackend cpu (or gpu; actual fallback is logged)
 * Without explicit paths these use filesDir/model_assets and filesDir/audio-fixtures respectively.
 * Missing files are explicit assumptions; graph/runtime failures are NOT swallowed as skips.
 * Run the TIGER test separately on memory-constrained emulators. No microphone or AudioTrack is used.
 */
class AudioModelSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferred get() = arguments.getString("audioBackend") ?: "cpu"

    @Test fun zipformerRecognizesPinnedEnglishSpeechAndRecoversAfterCancellation() {
        val directory = directory("speech-recognition", ZipformerEngine.SMALL_MODEL, "tokens.txt")
        val speech = fixture()
        ZipformerEngine(directory, preferred).use { engine ->
            assertThrows(CancellationException::class.java) {
                engine.transcribe(AudioTaskRequest(speech, 16000, isCancelled = { true }))
            }
            val result = engine.transcribe(speech)
            assertEquals("WHAT TIME IS IT", result.text.uppercase(Locale.ROOT).trim(' ', '?', '.', '!'))
            assertEquals("en", result.language)
            assertEquals(1, result.windows)
            Log.i("AudioModelSmoke", "Zipformer ${result.backend} ${result.inferenceMs}ms text=${result.text} fallback=${result.fallbackReason}")
        }
    }

    @Test fun crepeRecoversKnownSinePitchesRatherThanMerelyReturningAnArray() {
        val directory = directory("pitch-detection", "crepe_full_fp16.tflite")
        AudioTasks.create("pitch-detection", directory, preferred).use { engine ->
            for (frequency in listOf(220, 440, 880)) {
                val pcm = FloatArray(1024) { (0.25 * sin(2 * PI * frequency * it / 16000)).toFloat() }
                val result = engine.run(AudioTaskRequest(pcm, 16000))
                val point = (result.metrics.getValue("curve") as List<*>).single() as Map<*, *>
                val hz = (point["hz"] as Number).toDouble()
                val confidence = (point["confidence"] as Number).toDouble()
                assertEquals(frequency.toDouble(), hz, frequency * 0.02)
                assertTrue("Voiced sine confidence=$confidence", confidence > 0.8)
                Log.i("AudioModelSmoke", "CREPE ${engine.backend} input=$frequency output=$point")
            }
        }
    }

    @Test fun pannsClassifiesTheKnownSpeechFixtureAsSpeech() {
        val directory = directory("audio-classification", "cnn14_audioset_fp16.tflite", "mel_basis.bin", "audioset_labels.txt")
        AudioTasks.create("audio-classification", directory, preferred).use { engine ->
            val result = engine.run(AudioTaskRequest(fixture(), 16000))
            val top = result.metrics.getValue("top5") as List<*>
            val speech = top.map { it as Map<*, *> }.firstOrNull { it["label"] == "Speech" }
            assertNotNull("Speech must appear in top 5: $top", speech)
            assertTrue((speech!!["score"] as Number).toDouble() > 0.2)
            assertTrue(result.metrics.getValue("attribution").toString().contains("CC-BY-4.0"))
            Log.i("AudioModelSmoke", "PANNs ${engine.backend} $top")
        }
    }

    @Test fun dacCodesDecodeToCorrelatedSpeechWithDocumentedPerWindowTrim() {
        val directory = directory("audio-codec", "dac_16khz_encoder_fp16.tflite", "dac_16khz_deconly_zs_fp16.tflite", "dac_rvq.bin")
        val input = fixture().copyOf(16000)
        AudioTasks.create("audio-codec", directory, preferred).use { engine ->
            val result = engine.run(AudioTaskRequest(input, 16000))
            val decoded = result.waveforms.single { it.name == "Decoded" }.samples
            assertEquals(15992, decoded.size)
            val chunks = result.metrics.getValue("codeChunks") as List<*>
            val codes = (chunks.single() as DacCodeChunk).codes
            assertEquals(600, codes.size)
            assertTrue(codes.all { it in 0..1023 })
            assertTrue(codes.toSet().size > 10)
            // Pinned decoder removes eight input samples, not a pure zero-lag waveform copy.
            // Linux CPU reference: zero-lag corr=.1855; input[8:] corr=.9553, SNR=10.32 dB.
            // Keep the unaligned SNR visible in the API; use the known fixed alignment for fidelity.
            val correlation = correlation(input.copyOfRange(8, input.size), decoded)
            assertTrue("Aligned roundtrip must preserve speech correlation: $correlation", correlation > 0.9)
            assertTrue(rms(decoded) in 0.001..0.8)
            Log.i("AudioModelSmoke", "DAC ${engine.backend} corr=$correlation metrics=${result.metrics}")
        }
    }

    @Test fun basicPitchTranscribesSustainedA4WithOnsetAndOffset() {
        val directory = directory("music-transcription", "basicpitch.tflite")
        val input = FloatArray(43844) { i ->
            val afterStart = i - 2205
            val end = 39634
            if (afterStart < 0 || i >= end) 0f else
                (0.25 * minOf(1.0, afterStart / 220.5, (end - i) / 220.5) * sin(2 * PI * 440 * i / 22050)).toFloat()
        }
        AudioTasks.create("music-transcription", directory, preferred).use { engine ->
            val result = engine.run(AudioTaskRequest(input, 22050))
            val notes = (result.metrics.getValue("notes") as List<*>).map { it as Map<*, *> }
            val a4 = notes.firstOrNull { (it["pitch"] as Number).toInt() == 69 }
            assertNotNull("Expected A4 note: $notes", a4)
            val start = (a4!!["onsetSeconds"] as Number).toDouble()
            val end = (a4["endSeconds"] as Number).toDouble()
            assertTrue("A4 onset must be near signal attack: $start", start in 0.0..0.3)
            assertTrue("A4 must sustain: $start..$end", end - start > 1.0)
            Log.i("AudioModelSmoke", "BasicPitch ${engine.backend} $notes")
        }
    }

    @Test fun cmganDenoisesDeterministicNoisySpeechAndHandlesSilence() {
        val directory = directory("speech-enhancement", "cmgan_fp16.tflite")
        val clean = fixture()
        val random = Random(21)
        val noisy = FloatArray(clean.size) { (clean[it] + random.nextGaussian() * 0.02).toFloat().coerceIn(-1f, 1f) }
        AudioTasks.create("speech-enhancement", directory, preferred).use { engine ->
            val result = engine.run(AudioTaskRequest(noisy, 16000))
            val enhanced = result.waveforms.single { it.name == "Enhanced" }.samples
            assertEquals(noisy.size, enhanced.size)
            assertTrue(enhanced.all { it.isFinite() })
            assertTrue(correlation(clean, enhanced) > 0.5)
            assertTrue("Enhancement must change the noisy signal", rms(FloatArray(noisy.size) { noisy[it] - enhanced[it] }) > 0.001)
            val before = snr(clean, noisy)
            val after = snr(clean, enhanced)
            assertTrue("Known white-noise fixture SNR must improve: $before -> $after", after > before)
            val silence = engine.run(AudioTaskRequest(FloatArray(1600), 16000)).waveforms.single { it.name == "Enhanced" }
            assertTrue(silence.samples.all { it == 0f })
            Log.i("AudioModelSmoke", "CMGAN ${engine.backend} SNR=$before->$after")
        }
    }

    @Test fun matchaProducesDeterministicNonzeroEnglishSpeechWithCpuCfm() {
        val directory = directory("text-to-speech", "config.json", "g2p_meta.json", "g2p_dict.txt.gz", "dp_g2p_matcha_fp16.tflite",
            "emb.bin", "matcha_textenc_fp16.tflite", "matcha_decoder_fp16.tflite", "matcha_vocoder_fp16.tflite")
        val asrDirectory = directory("speech-recognition", ZipformerEngine.SMALL_MODEL, "tokens.txt")
        val result = MatchaEngine(directory, preferred).use { engine ->
            var cancelled = false
            assertThrows(CancellationException::class.java) {
                engine.synthesize("The rain is soft today.", AudioControl({ cancelled }) { stage, current, _ ->
                    if (stage == "Matcha CFM (CPU)" && current == 1) cancelled = true
                }, seed = 0)
            }
            val result = engine.synthesize("The rain is soft today.", seed = 0)
            assertEquals(22050, result.sampleRate)
            assertTrue(result.samples.size in 22050..131072)
            assertTrue(result.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(rms(result.samples) in 0.01..0.8)
            assertTrue(result.backendDetails.contains("matcha_decoder_fp16.tflite=CPU"))
            val repeated = engine.synthesize("The rain is soft today.", seed = 0)
            assertArrayEquals(result.samples, repeated.samples, 1e-4f)
            result
        }
        // Release all TTS graphs before the ASR semantic oracle to bound peak native memory.
        ZipformerEngine(asrDirectory, "cpu").use { asr ->
            val recognized = asr.transcribe(AudioTaskRequest(result.samples, result.sampleRate)).text.uppercase(Locale.ROOT)
            // This paired model's known Linux result is "TO DAY"; do not claim exact WER=0
            // against "TODAY". Whitespace-insensitive text is a useful waveform intelligibility
            // smoke while preserving the original transcript in the logged evidence.
            assertEquals("THERAINISSOFTTODAY", recognized.trim(' ', '?', '.', '!').replace(" ", ""))
            Log.i("AudioModelSmoke", "Matcha ${result.backendDetails} rms=${rms(result.samples)} ASR=$recognized")
        }
    }

    @Test fun matchaNeuralOovMatchesPinnedDontFixture() {
        val directory = directory("text-to-speech", "config.json", "g2p_meta.json", "g2p_dict.txt.gz", "dp_g2p_matcha_fp16.tflite")
        val compiler = AudioCompiler("cpu")
        MatchaG2P(directory, compiler).use { g2p ->
            // "don't" is deliberately absent from the pinned dictionary; source iOS fixture starts dˈɔnt.
            assertArrayEquals(intArrayOf(46,156,76,56,62), g2p.phonemize("don't", AudioControl()))
            assertTrue(compiler.snapshot.details.contains("dp_g2p_matcha_fp16.tflite=CPU"))
        }
    }

    @Test fun tigerSpeechOnlyFixtureGoesPrimarilyToDialogAndAllStemsHaveSignal() {
        val directory = directory("audio-source-separation", "tiger_dialog_fp16.tflite", "tiger_effect_fp16.tflite", "tiger_music_fp16.tflite")
        AudioTasks.create("audio-source-separation", directory, preferred).use { engine ->
            val result = engine.run(AudioTaskRequest(fixture(), 16000))
            assertEquals(listOf("dialog", "effect", "music"), result.waveforms.map { it.name })
            val waves = result.waveforms.associateBy { it.name }
            val dialog = waves.getValue("dialog")
            assertEquals(44100, dialog.sampleRate)
            assertTrue(dialog.samples.all { it.isFinite() })
            assertTrue(rms(dialog.samples) > 0.001)
            assertTrue("Speech-only source should be dialog dominant", rms(dialog.samples) > rms(waves.getValue("effect").samples))
            assertTrue(rms(dialog.samples) > rms(waves.getValue("music").samples))
            assertTrue(result.waveforms.all { it.samples.size == dialog.samples.size && it.samples.all(Float::isFinite) })
            Log.i("AudioModelSmoke", "TIGER ${engine.backend} " + result.waveforms.joinToString { "${it.name} rms=${rms(it.samples)}" })
        }
    }

    private fun directory(task: String, vararg files: String): File {
        val root = arguments.getString("audioModels")?.let(::File) ?: File(instrumentation.targetContext.filesDir, "model_assets")
        val directory = File(root, task)
        assumeTrue("Install verified $task resources in $directory", files.all { File(directory, it).isFile })
        return directory
    }

    private fun fixture(): FloatArray {
        val file = arguments.getString("audioFixture")?.let(::File)
            ?: File(instrumentation.targetContext.filesDir, "audio-fixtures/what_time_is_it.wav")
        assumeTrue("Push pinned upstream what_time_is_it.wav and set audioFixture", file.isFile)
        assertEquals("Use the exact pinned fixture", 43778L, file.length())
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("420d6c66e7385e8d1949da6b5f9aecc979a45e8268a5e6cc1b3491ef77f58f77", hash)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val name = String(bytes, cursor, 4, Charsets.US_ASCII)
            val length = buffer.getInt(cursor + 4)
            require(length >= 0 && length <= bytes.size - cursor - 8)
            if (name == "data") {
                buffer.position(cursor + 8)
                return FloatArray(length / 2) { buffer.short / 32768f }
            }
            cursor += 8 + length + length % 2
        }
        error("Pinned WAV is missing PCM data")
    }

    private fun rms(pcm: FloatArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
    private fun snr(reference: FloatArray, actual: FloatArray): Double =
        10 * log10(reference.sumOf { it.toDouble() * it } / reference.indices.sumOf { val e = reference[it].toDouble() - actual[it]; e * e })

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val meanA = a.take(n).average(); val meanB = b.take(n).average()
        var aa = 0.0; var bb = 0.0; var ab = 0.0
        for (i in 0 until n) { val x = a[i] - meanA; val y = b[i] - meanB; aa += x*x; bb += y*y; ab += x*y }
        return ab / sqrt(aa * bb)
    }
}
