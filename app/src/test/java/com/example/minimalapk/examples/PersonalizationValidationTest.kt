package com.example.minimalapk.examples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationValidationTest {
    @Test
    fun everyCategoryNeedsFiveDistinctPhotosAndHasABoundedSampleCount() {
        val ready = samplesPerCategory(5)
        assertEquals(listOf(5, 5, 5, 5), validatePersonalizationSamples(ready))
        assertEquals(listOf(50, 50, 50, 50), validatePersonalizationSamples(samplesPerCategory(50)))
        listOf(
            emptyList(),
            samplesPerCategory(4),
            ready.dropLast(1),
            ready.filterNot { it.label == 3 },
            samplesPerCategory(50) + TrainingSample(floatArrayOf(999f, 999f), 0),
        ).forEach { assertThrows(IllegalArgumentException::class.java) { validatePersonalizationSamples(it) } }
    }

    @Test
    fun copiedPhotosCannotInflateCountsOrReceiveContradictoryLabels() {
        val ready = samplesPerCategory(5)
        val duplicateInSameClass = ready.toMutableList().apply {
            this[1] = TrainingSample(ready[0].bottleneck.copyOf(), 0)
        }
        val duplicateInAnotherClass = ready.toMutableList().apply {
            this[5] = TrainingSample(ready[0].bottleneck.copyOf(), 1)
        }
        assertEquals(ready[0].fingerprint, duplicateInSameClass[1].fingerprint)
        listOf(duplicateInSameClass, duplicateInAnotherClass).forEach { samples ->
            assertThrows(IllegalArgumentException::class.java) { validatePersonalizationSamples(samples) }
        }
    }

    @Test
    fun invalidCategoryAndNonfiniteOrInconsistentFeaturesAreRejectedBeforeTraining() {
        val ready = samplesPerCategory(5)
        listOf(
            TrainingSample(floatArrayOf(9f, 9f), -1),
            TrainingSample(floatArrayOf(9f, 9f), CLASS_COUNT),
            TrainingSample(floatArrayOf(Float.NaN, 0f), 0),
            TrainingSample(floatArrayOf(Float.POSITIVE_INFINITY, 0f), 0),
            TrainingSample(floatArrayOf(1f), 0),
            TrainingSample(floatArrayOf(), 0),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { validatePersonalizationSamples(listOf(bad) + ready.drop(1)) }
        }
    }

    @Test
    fun categoryNamesMustBeCompleteDistinctAndUsableAsSingleLineLabels() {
        assertNull(personalizationCategoryProblem(listOf(" 螺母 ", "螺栓", "垫片", "支架")))
        listOf(
            listOf("A", "B", "C"),
            listOf("", "B", "C", "D"),
            listOf("A", " a ", "C", "D"),
            listOf("A\nB", "B", "C", "D"),
            listOf("A".repeat(MAX_CATEGORY_NAME_LENGTH + 1), "B", "C", "D"),
        ).forEach { assertNotNull(personalizationCategoryProblem(it)) }
        assertThrows(IllegalArgumentException::class.java) { PersonalizationProfile(listOf(" A ", "B", "C", "D")) }
        listOf(Float.NaN, .49f, .96f).forEach { threshold ->
            assertThrows(IllegalArgumentException::class.java) { PersonalizationProfile(listOf("A", "B", "C", "D"), threshold) }
        }
    }

    @Test
    fun cameraAndPhotoResultsUseSavedCategoryOrderConfidenceAndUnknownThreshold() {
        val profile = PersonalizationProfile(listOf("螺母", "螺栓", "垫片", "支架"), .75f)
        val unknown = personalizationResultText(floatArrayOf(.1f, .74f, .1f, .06f), profile)
        assertTrue(unknown.startsWith("未知"))
        assertTrue(unknown.contains("螺栓"))
        assertTrue(unknown.contains("74.0%"))
        assertTrue(unknown.contains("75.0%"))
        val accepted = personalizationResultText(floatArrayOf(.1f, .75f, .1f, .05f), profile)
        assertFalse(accepted.startsWith("未知"))
        assertTrue(accepted.startsWith("螺栓"))
        val legacy = personalizationResultText(floatArrayOf(.05f, .05f, .85f, .05f), null)
        assertTrue(legacy.startsWith("旧类别 3（未命名）"))
        assertThrows(IllegalArgumentException::class.java) {
            personalizationResultText(floatArrayOf(Float.NaN, 0f, 0f, 0f), profile)
        }
    }

    @Test
    fun invalidInferenceFeaturesFailInsidePredictionInsteadOfReachingTheUi() {
        val head = SoftmaxHead(2)
        assertThrows(IllegalArgumentException::class.java) { head.predict(floatArrayOf(Float.NaN, 0f)) }
        assertThrows(IllegalArgumentException::class.java) { head.predict(floatArrayOf(Float.POSITIVE_INFINITY, 0f)) }
    }

    private fun samplesPerCategory(count: Int): List<TrainingSample> = (0 until CLASS_COUNT).flatMap { label ->
        List(count) { index -> TrainingSample(floatArrayOf(label.toFloat(), index.toFloat()), label) }
    }
}
