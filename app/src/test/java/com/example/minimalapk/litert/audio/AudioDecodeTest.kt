/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; https://www.apache.org/licenses/LICENSE-2.0
 * Adapted and extended from litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * Model Zoo models/{zipformer,crepe,basicpitch,dac}/ math tests.
 */
package com.example.minimalapk.litert.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AudioDecodeTest {
    @Test fun catalogTokenVocabularyIncludesNonLogitFstDisambiguationSymbols() {
        val text = (0 until 500).joinToString("\n") { "piece$it $it" } + "\n#0 500\n#1 501\n"
        val pieces = ZipformerEngine.parseTokens(text)
        assertEquals(502, pieces.size)
        assertEquals("#1", pieces[501])
        assertThrows(IllegalArgumentException::class.java) { ZipformerEngine.parseTokens(text + "bad 501\n") }
    }

    @Test fun ctcDropsRepeatsButBlankAllowsRepeatedWordAndPaddedLogitsAreIgnored() {
        val pieces = mapOf(1 to "▁HEL", 2 to "LO", 3 to "▁WORLD", 4 to "▁PAD")
        assertEquals("HELLO WORLD", ZipformerEngine.decode(logits(1, 1, 0, 2, 2, 3, 0), 7, pieces))
        assertEquals("WORLD WORLD", ZipformerEngine.decode(logits(3, 3, 0, 3, 4), 4, pieces))
        assertEquals("", ZipformerEngine.decode(logits(0, 0), 2, pieces))
        assertThrows(IllegalArgumentException::class.java) { ZipformerEngine.decode(FloatArray(2), 1, pieces) }
    }

    @Test fun fixedZipformerMasksUseCeilStridesAndNeverExposePadding() {
        for (index in 0..3) {
            val stride = 1 shl index
            val bias = ZipformerEngine.attentionBias(77, index)
            assertEquals((77 + stride - 1) / stride, bias.count { it == 0f })
            assertTrue(bias.drop((77 + stride - 1) / stride).all { it == -1000f })
            assertTrue(ZipformerEngine.attentionBias(0, index).all { it == -1000f })
        }
    }

    @Test fun overlapDedupDoesNotCarryStateIntoANewUtterance() {
        assertEquals("THE RAIN IS SOFT TODAY", ZipformerEngine.mergeOverlap("THE RAIN IS SOFT", "IS SOFT TODAY"))
        assertEquals("GO GO HOME", ZipformerEngine.mergeOverlap("GO GO", "GO HOME"))
        assertEquals("A B", ZipformerEngine.mergeOverlap("A", "B"))
        assertEquals("RAIN", ZipformerEngine.mergeOverlap("", "RAIN"))
    }

    @Test fun asrWindowDedupIsLimitedToWordsActuallyEmittedInSharedAudio() {
        val hop = ZipformerEngine.MAX_WINDOW_SAMPLES - 32000
        val early = listOf(ZipformerEngine.Companion.CtcWord("HELLO", 10, 12))
        val next = listOf(ZipformerEngine.Companion.CtcWord("HELLO", 10, 12))
        assertEquals("HELLO HELLO", ZipformerEngine.mergeWindow("HELLO", early, next, 0, hop))
        val inOverlap = listOf(ZipformerEngine.Companion.CtcWord("HELLO", 360, 362))
        assertEquals("HELLO", ZipformerEngine.mergeWindow("HELLO", inOverlap, next, 0, hop))
        val laterInNext = listOf(ZipformerEngine.Companion.CtcWord("HELLO", 200, 202))
        assertEquals("HELLO HELLO", ZipformerEngine.mergeWindow("HELLO", inOverlap, laterInNext, 0, hop))
    }

    @Test fun crepeWeightedPeakRecoversA4AndZeroActivationsAreUnvoiced() {
        // A4 is 6551.3179 cents; interpolate neighboring bins rather than return argmax center.
        val position = (1200 * kotlin.math.log2(44.0) - 1997.3794084376191) / 20
        val bin = position.toInt()
        val activations = FloatArray(360)
        activations[bin] = (1 - (position - bin)).toFloat()
        activations[bin + 1] = (position - bin).toFloat()
        val decoded = CrepeDetector.decode(activations)
        assertEquals(440f, decoded.hz, 0.001f)
        assertEquals("A", decoded.note); assertEquals(4, decoded.octave); assertEquals(0, decoded.cents)
        assertEquals(0f, CrepeDetector.decode(FloatArray(360)).hz, 0f)
        assertTrue(CrepeDetector.normalize(FloatArray(1024) { 0.5f }).all { it == 0f })
        val normalized = CrepeDetector.normalize(FloatArray(1024) { if (it % 2 == 0) 0.25f else -0.75f })
        assertEquals(0f, normalized.average().toFloat(), 1e-6f)
        assertEquals(1.0, normalized.sumOf { it.toDouble() * it } / normalized.size, 1e-6)
    }

    @Test fun basicPitchOnsetSustainMinimumDurationAndSamePitchRetriggerAreDecoded() {
        val note = Array(10) { FloatArray(88) }
        val onset = Array(10) { FloatArray(88) }
        for (t in 1..8) note[t][48] = if (t == 3) 0.9f else 0.6f
        onset[1][48] = 0.7f; onset[5][48] = 0.8f
        note[0][0] = 0.9f; onset[0][0] = 0.9f // rejected as one-frame noise
        val events = BasicPitchTranscriber.decode(note, onset)
        assertEquals(2, events.size)
        assertEquals(69, events[0].midi)
        assertEquals(BasicPitchTranscriber.FRAME_SECONDS, events[0].startSec, 0.0)
        assertEquals(5 * BasicPitchTranscriber.FRAME_SECONDS, events[0].endSec, 0.0)
        assertEquals(0.9f, events[0].amplitude, 0f)
        assertEquals(5 * BasicPitchTranscriber.FRAME_SECONDS, events[1].startSec, 0.0)
        assertEquals(9 * BasicPitchTranscriber.FRAME_SECONDS, events[1].endSec, 0.0)
    }

    @Test fun basicPitchOverlapWindowsCoverEveryRealFrameExactlyOnceIncludingPartialTail() {
        for (samples in listOf(1, 43844, 43845, 80000, 30 * 22050)) {
            val windows = AudioDsp.windowCount(samples, BasicPitchTranscriber.SAMPLES, BasicPitchTranscriber.WINDOW_HOP)
            val covered = IntArray(samples / BasicPitchTranscriber.FFT_HOP + 1)
            for (window in 0 until windows) {
                val base = window * BasicPitchTranscriber.WINDOW_HOP / BasicPitchTranscriber.FFT_HOP
                for (frame in BasicPitchTranscriber.keptFrames(window, windows, covered.size)) covered[base + frame]++
            }
            assertTrue("$samples samples has uncovered/duplicated frames", covered.all { it == 1 })
        }
    }

    @Test fun dacRvqUsesRawCodebookForDecodeAndSubtractsEachResidualStage() {
        val bytes = ByteArray(DacRVQ.WEIGHT_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val perBook = DacRVQ.WEIGHT_BYTES / 4 / DacRVQ.BOOKS
        // Synthetic learned projection: first hidden channel only, ±1 code vectors.
        // Stage 0 sees +.7/- .7 => codes +1/-1; subtraction flips signs for stage 1.
        for (book in 0 until DacRVQ.BOOKS) {
            val base = book * perBook
            fun put(index: Int, value: Float) { data.putFloat((base + index) * 4, value) }
            put(0, 1f); put(DacRVQ.DIM, -1f)
            put(DacRVQ.SIZE * DacRVQ.DIM, 1f)
            put(DacRVQ.SIZE * DacRVQ.DIM + DacRVQ.DIM * DacRVQ.HIDDEN + DacRVQ.DIM, 1f)
        }
        val rvq = DacRVQ(bytes)
        val latent = FloatArray(DacRVQ.HIDDEN * 2)
        latent[0] = 0.7f; latent[1] = -0.7f
        val codes = rvq.encode(latent, 2)
        assertArrayEquals(intArrayOf(0, 1, 1, 0, 0, 1, 1, 0), codes.copyOf(8))
        assertTrue(rvq.decode(codes, 2).all { it == 0f }) // twelve alternating ±1 stages sum to zero
        val allPositive = rvq.decode(IntArray(DacRVQ.BOOKS * 2), 2)
        assertEquals(12f, allPositive[0], 0f)
        assertEquals(12f, allPositive[1], 0f)
        assertTrue(allPositive.drop(2).all { it == 0f })
        assertThrows(IllegalArgumentException::class.java) { rvq.decode(IntArray(24) { 1024 }, 2) }
        assertThrows(CancellationException::class.java) { rvq.encode(latent, 2, AudioControl({ true })) }
    }

    @Test fun compilerOnlyFallsBackOnCompilationAndReportsActualPerGraphPlacement() {
        val compiler = AudioCompiler("gpu")
        val attempts = mutableListOf<com.google.ai.edge.litert.Accelerator>()
        val value = compiler.create("encoder") {
            attempts += it
            if (it == com.google.ai.edge.litert.Accelerator.GPU) error("unsupported op")
            "cpu graph"
        }
        assertEquals("cpu graph", value)
        assertEquals(2, attempts.size)
        assertEquals("CPU", compiler.snapshot.actual)
        assertTrue(compiler.snapshot.fallbackReason!!.contains("unsupported op"))
        compiler.create("vocoder") { Unit }
        assertEquals("GPU + CPU", compiler.snapshot.actual)
        assertThrows(CancellationException::class.java) { compiler.create("cancelled") { throw CancellationException() } }
    }

    @Test fun upstreamProgressLambdaConstructorAndCancellationConstructorAreBothUsable() {
        var stage = ""
        val oldStyle = AudioTaskRequest(floatArrayOf(0f), 16000) { name, _, _ -> stage = name }
        oldStyle.onProgress("a", 0, 1)
        assertEquals("a", stage)
        val cancellable = AudioTaskRequest(floatArrayOf(0f), 16000, isCancelled = { true })
        assertTrue(cancellable.isCancelled())
        assertEquals(6, AudioTasks.ids.size)
    }

    private fun logits(vararg ids: Int): FloatArray = FloatArray(ids.size * 500) { -10f }.also { values ->
        ids.forEachIndexed { index, id -> values[index * 500 + id] = 10f }
    }
}
