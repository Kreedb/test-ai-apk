/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; https://www.apache.org/licenses/LICENSE-2.0
 * Adapted and extended from litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * Model Zoo models/{zipformer,tiger,cmgan,panns}/ math tests.
 */
package com.example.minimalapk.litert.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class AudioDspTest {
    @Test fun exactKaldiTablesMatchPinnedUpstreamBinaryHashes() {
        assertEquals("a2f6d8ac704ea144d0e8bcde479f1c53ec674e1ed6fa8094590e64cacee30890", hash(ZipformerFbank.melTable()))
        assertEquals("36444c5814867e099a289b76c2a6adc4c7fe721bb64edc54910dc74607a7ca6f", hash(ZipformerFbank.window()))
    }

    @Test fun zipformerPoveyPreemphasisAndReflectMatchIndependentDirectDftGolden() {
        val audio = FloatArray(640) { (0.25 * sin(2 * PI * 440 * it / 16000)).toFloat() }
        val features = ZipformerFbank().compute(audio)
        // Generated with a double-precision direct DFT, from the pinned .bin tables, not AudioFft.
        val bins = intArrayOf(0, 1, 7, 10, 12, 13, 14, 15, 20, 40, 60, 79)
        val golden = floatArrayOf(-6.00925667f, -5.38776253f, -2.90380929f, -1.34296098f, 0.51286159f,
            1.85678047f, 2.28741552f, 2.46207434f, -1.34139680f, -3.97766539f, -5.39702236f, -9.06817846f)
        for (i in bins.indices) assertEquals("reflected first frame bin ${bins[i]}", golden[i], features[0][bins[i]], 0.0005f)
        val center = floatArrayOf(1.71601415f, 2.96027012f, 2.52366127f, -0.21468041f)
        for (i in center.indices) assertEquals(center[i], features[1][13 + i], 0.0001f)
        assertEquals(4, features.size)
    }

    @Test fun zipformerSilenceDcAndVeryShortReflectAreFiniteAndHaveNoCmn() {
        val fbank = ZipformerFbank()
        for (input in listOf(FloatArray(400), FloatArray(400) { 0.25f }, FloatArray(80) { -0.5f })) {
            fbank.compute(input).forEach { row -> row.forEach { assertEquals(ln(1.1920928955078125e-7f), it, 0f) } }
        }
        assertEquals(0, fbank.frames(79))
        assertEquals(1, fbank.frames(80))
        assertEquals(1600, fbank.frames(255920))
        assertEquals(0, fbank.compute(FloatArray(0)).size)
        assertThrows(IllegalArgumentException::class.java) { fbank.compute(floatArrayOf(Float.NaN)) }
    }

    @Test fun pannsUsesPowerDecibelsAndPeriodicHann() {
        // A sparse reference mel basis selecting exactly DFT bin 32; 1000 Hz at 32 kHz.
        val basis = FloatArray(PannsMel.MELS * PannsMel.BINS)
        basis[32] = 1f
        val mel = PannsMel(bytes(basis))
        val pcm = FloatArray(PannsMel.SAMPLES) { (0.5 * sin(2 * PI * 1000 * it / 32000)).toFloat() }
        val features = mel.compute(pcm)
        // A periodic Hann gives sinusoid magnitude A*N/4=128 -> power=16384 -> 42.1442 dB.
        assertEquals(42.144199f, features[10 * PannsMel.MELS], 0.0001f)
        assertEquals(-100f, features[10 * PannsMel.MELS + 1], 0f)
        assertTrue(mel.compute(floatArrayOf(0f)).all { it == -100f })
    }

    @Test fun tigerInverseReconstructsNonzeroToneAndCenterCrop() {
        val frames = 9
        val bin = 17
        val amplitude = 0.25f
        val real = FloatArray(TigerIstft.BINS * frames)
        val imag = FloatArray(real.size)
        // Analytical STFT of a bin-centered cosine times periodic Hann: k and k±1 only.
        for (t in 0 until frames) {
            val phase = 2 * PI * bin * (t * TigerIstft.HOP - TigerIstft.WINDOW / 2) / TigerIstft.WINDOW
            for ((k, scale) in listOf(bin to 0.25, bin - 1 to -0.125, bin + 1 to -0.125)) {
                real[k * frames + t] = (amplitude * TigerIstft.WINDOW * scale * cos(phase)).toFloat()
                imag[k * frames + t] = (amplitude * TigerIstft.WINDOW * scale * sin(phase)).toFloat()
            }
        }
        val waveform = TigerIstft.reconstruct(real, imag, frames, 4096)
        for (i in waveform.indices) assertEquals((amplitude * cos(2 * PI * bin * i / TigerIstft.WINDOW)).toFloat(), waveform[i], 2e-6f)
        assertThrows(IllegalArgumentException::class.java) { TigerIstft.reconstruct(real, imag, frames, 531968) }
    }

    @Test fun cmganPowerDecompressionHammingAndOverlapRecoverKnownWaveform() {
        val real = FloatArray(CmganEnhancer.FRAMES * CmganEnhancer.BINS)
        val imag = FloatArray(real.size)
        val amplitude = 0.2
        val bin = 11
        // Periodic Hamming = .54 - .46*cos: cosine STFT coefficients .27N, -.115N.
        for (t in 0 until CmganEnhancer.FRAMES) {
            val phase = 2 * PI * bin * (t * CmganEnhancer.HOP - CmganEnhancer.FFT / 2) / CmganEnhancer.FFT
            for ((k, scale) in listOf(bin to 0.27, bin - 1 to -0.115, bin + 1 to -0.115)) {
                val magnitude = abs(amplitude * CmganEnhancer.FFT * scale).pow(0.3)
                val sign = if (scale < 0) -1 else 1
                real[t * CmganEnhancer.BINS + k] = (magnitude * sign * cos(phase)).toFloat()
                imag[t * CmganEnhancer.BINS + k] = (magnitude * sign * sin(phase)).toFloat()
            }
        }
        val output = CmganEnhancer.inverse(real, imag)
        for (i in output.indices step 13) assertEquals((amplitude * cos(2 * PI * bin * i / CmganEnhancer.FFT)).toFloat(), output[i], 3e-6f)
        assertTrue(CmganEnhancer.inverse(FloatArray(real.size), FloatArray(real.size)).all { it == 0f })
    }

    @Test fun resamplingPreservesDcAndSuppressesOutOfBandAlias() {
        val control = AudioControl()
        val dc = AudioDsp.atRate(FloatArray(4800) { 0.25f }, 48000, 16000, control)
        assertEquals(1600, dc.size)
        dc.forEach { assertEquals(0.25f, it, 1e-6f) }
        val high = FloatArray(48000) { (0.5 * sin(2 * PI * 12000 * it / 48000)).toFloat() }
        val converted = AudioDsp.atRate(high, 48000, 16000, control)
        val energy = converted.drop(100).dropLast(100).sumOf { it.toDouble() * it }
        assertTrue("12 kHz must not alias as a strong 4 kHz tone", sqrt(energy / (converted.size - 200)) < 0.001)
        val sameRate = AudioDsp.atRate(floatArrayOf(-2f, 0f, 2f), 16000, 16000, control)
        assertArrayEquals(floatArrayOf(-1f, 0f, 1f), sameRate, 0f)
    }

    @Test fun batchWindowsAndReflectionHaveNoUnboundedTail() {
        assertEquals(1, AudioDsp.windowCount(16000, 16000, 16000))
        assertEquals(2, AudioDsp.windowCount(16001, 16000, 16000))
        assertEquals(3, AudioDsp.windowCount(TigerSeparator.CHUNK + 2 * TigerSeparator.HOP, TigerSeparator.CHUNK, TigerSeparator.HOP))
        assertArrayEquals(floatArrayOf(3f, 2f, 1f, 2f, 3f, 0f, 3f, 2f),
            AudioDsp.reflectedWindow(floatArrayOf(1f, 2f, 3f), 0, 4, 2), 0f)
        assertEquals(0, AudioDsp.kaldiIndex(-1, 1))
        assertEquals(0, AudioDsp.kaldiIndex(100, 1))
        assertThrows(IllegalArgumentException::class.java) { AudioDsp.atRate(FloatArray(16000 * 30 + 1), 16000, 16000, AudioControl()) }
        assertThrows(IllegalArgumentException::class.java) { AudioDsp.atRate(floatArrayOf(Float.POSITIVE_INFINITY), 16000, 16000, AudioControl()) }
        assertThrows(IllegalArgumentException::class.java) { AudioDsp.atRate(floatArrayOf(0f), 0, 16000, AudioControl()) }
    }

    @Test fun cancellationAbortsDspAndProgressCannotPublishAfterCancellation() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ZipformerFbank().compute(FloatArray(16000), AudioControl(isCancelled = { ++checks == 3 }))
        }
        var cancelled = false
        val control = AudioControl({ cancelled }) { _, _, _ -> cancelled = true }
        assertThrows(CancellationException::class.java) { control.progress("test", 1, 1) }
        assertThrows(CancellationException::class.java) { AudioDsp.atRate(floatArrayOf(0f), 16000, 16000, control) }
    }

    @Test fun engineResourceGuardEnforcesSameThreadAndRejectsCallbackReentry() {
        val guard = AudioThread()
        var closes = 0
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { guard.close { closes++ } } catch (e: Throwable) { failure.set(e) } }
        worker.start(); worker.join()
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(0, closes)
        guard.run {
            assertThrows(IllegalStateException::class.java) { guard.run { error("Reentrant body must not execute") } }
            assertThrows(IllegalStateException::class.java) { guard.close { closes++ } }
        }
        guard.close { closes++ }; guard.close { closes++ }
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { guard.run { error("Closed body must not execute") } }
    }

    private fun bytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).also {
        values.forEach(it::putFloat)
    }.array()

    private fun hash(values: FloatArray): String = MessageDigest.getInstance("SHA-256").digest(bytes(values)).joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
}
