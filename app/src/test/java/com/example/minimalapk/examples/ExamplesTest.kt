package com.example.minimalapk.examples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SuperResolutionTest {
    @Test
    fun channelIsClampedAndRounded() {
        assertEquals(0, channelToByte(-0.5f))
        assertEquals(0, channelToByte(0f))
        assertEquals(128, channelToByte(127.5f))
        assertEquals(255, channelToByte(255f))
        assertEquals(255, channelToByte(300f))
    }
}

class SoftmaxHeadTest {
    @Test
    fun softmaxIsAProbabilityDistribution() {
        val scores = softmax(floatArrayOf(1f, 2f, 3f, 2f))
        assertTrue(abs(scores.sum() - 1f) < 1e-5f)
        assertEquals(2, scores.indices.maxBy { scores[it] })
        assertTrue(scores.all { it in 0f..1f })
    }

    @Test
    fun trainingSeparatesTwoClasses() {
        val head = SoftmaxHead(features = 3, classes = 2)
        val samples = listOf(
            TrainingSample(floatArrayOf(1f, 0f, 0f), 0),
            TrainingSample(floatArrayOf(0f, 1f, 0f), 1),
        )
        repeat(3000) { head.train(samples) }
        assertTrue(head.predict(floatArrayOf(1f, 0f, 0f))[0] > 0.9f)
        assertTrue(head.predict(floatArrayOf(0f, 1f, 0f))[1] > 0.9f)
    }

    @Test
    fun resetRestoresUntrainedPredictions() {
        val head = SoftmaxHead(features = 2, classes = 2)
        head.train(listOf(TrainingSample(floatArrayOf(1f, 0f), 0)))
        head.reset()
        val scores = head.predict(floatArrayOf(1f, 0f))
        assertEquals(0.5f, scores[0], 1e-5f)
        assertEquals(0.5f, scores[1], 1e-5f)
    }

    @Test
    fun batchesRepeatTheTailToKeepAFullBatch() {
        val samples = List(5) { TrainingSample(FloatArray(1), it % CLASS_COUNT) }
        val batches = trainingBatches(samples, batchSize = 3)
        assertEquals(2, batches.size)
        assertEquals(3, batches[0].size)
        assertEquals(listOf(2, 3, 4), batches[1].map { samples.indexOf(it) })
    }

    @Test
    fun emptySamplesProduceNoBatches() {
        assertTrue(trainingBatches(emptyList(), batchSize = 20).isEmpty())
    }

    @Test
    fun batchSizeIsCapped() {
        val samples = List(50) { TrainingSample(FloatArray(1), 0) }
        assertEquals(MAX_TRAIN_BATCH, trainingBatches(samples, batchSize = 100).first().size)
    }
}

class ExampleCatalogTest {
    @Test
    fun modelsAreHttpsAndPinnedToAByteCount() {
        listOf(ExampleCatalog.superResolution, ExampleCatalog.personalization).forEach { model ->
            assertTrue(model.url.startsWith("https://"))
            assertTrue(model.sizeBytes > 0)
        }
    }

    @Test
    fun samplingKeepsTheLongSideNearTheCap() {
        assertEquals(1, sampleSizeFor(2048, 2048))
        assertEquals(2, sampleSizeFor(4000, 2048))
        assertEquals(4, sampleSizeFor(5000, 2048))
        assertEquals(1, sampleSizeFor(100, 2048))
    }
}
