package com.example.minimalapk.examples

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class PersonalizationCheckpointTest {
    @get:Rule val folder = TemporaryFolder()

    private val names = listOf("螺母", "螺栓", "垫片", "支架")
    private val samples = listOf(
        TrainingSample(floatArrayOf(1f, 0f), 0),
        TrainingSample(floatArrayOf(0f, 1f), 1),
        TrainingSample(floatArrayOf(-1f, 0f), 2),
        TrainingSample(floatArrayOf(0f, -1f), 3),
    )

    @Test
    fun legacyUpgradeRequiresExplicitNamesAndPreservesEveryNumericByteAndContinuedTraining() {
        // Independent v1 fixture: the old on-disk format, including nonzero Adam moments/powers.
        val original = legacyBytes()
        val file = folder.newFile("legacy.bin").apply { writeBytes(original) }
        val head = SoftmaxHead(2).apply { restore(file) }
        val control = SoftmaxHead(2).apply { restore(file) }
        assertNull(head.profile)
        assertArrayEquals(original, file.readBytes()) // Merely opening an old file never rewrites it.
        assertTrue(personalizationResultText(floatArrayOf(.9f, .05f, .03f, .02f), head.profile).contains("旧类别 1（未命名）"))

        val profile = PersonalizationProfile(names, .85f)
        head.save(file, profile)
        val upgraded = file.readBytes()
        assertEquals(2, ByteBuffer.wrap(upgraded).int)
        assertArrayEquals(original.copyOfRange(4, original.size), upgraded.copyOfRange(4, original.size))
        val restored = SoftmaxHead(2).apply { restore(file) }
        assertEquals(profile, restored.profile)
        assertNull(restored.profile!!.trainingSampleCounts) // Never invent sample history for v1.

        repeat(12) {
            control.train(samples)
            restored.train(samples)
        }
        samples.forEach { assertArrayEquals(control.predict(it.bottleneck), restored.predict(it.bottleneck), 0f) }
        val controlFile = folder.newFile("control.bin").also { control.save(it) }
        restored.save(file)
        assertArrayEquals(controlFile.readBytes().drop(4).toByteArray(), file.readBytes().copyOfRange(4, original.size))
    }

    @Test
    fun namesThresholdAndLastSessionCountsRoundTripWithTheTrainedHead() {
        val head = SoftmaxHead(2)
        repeat(25) { head.train(samples) }
        val profile = PersonalizationProfile(names, .9f, listOf(5, 6, 8, 10))
        val file = folder.newFile("named.bin")
        head.save(file, profile)
        val restored = SoftmaxHead(2).apply { restore(file) }
        assertEquals(profile, restored.profile)
        samples.forEach { assertArrayEquals(head.predict(it.bottleneck), restored.predict(it.bottleneck), 0f) }
        restored.save(file) // A subsequent ordinary save must not strip the profile.
        assertEquals(profile, SoftmaxHead(2).apply { restore(file) }.profile)
    }

    @Test
    fun malformedMetadataOrTruncationCannotPartiallyReplaceWeightsOptimizerOrNames() {
        val current = SoftmaxHead(2)
        repeat(8) { current.train(samples) }
        val profile = PersonalizationProfile(names, .8f, List(CLASS_COUNT) { 5 })
        val good = folder.newFile("good.bin").also { current.save(it, profile) }
        val control = SoftmaxHead(2).apply { restore(good) }
        val before = good.readBytes()
        val invalid = listOf(
            before.copyOf(before.size - 1),
            before + byteArrayOf(1),
            namedBytes(listOf("重复", "重复", "垫片", "支架"), .8f, listOf(5, 5, 5, 5)),
            namedBytes(names, Float.NaN, listOf(5, 5, 5, 5)),
            namedBytes(names, .8f, listOf(5, 0, 5, 5)),
            namedBytes(names, .8f, listOf(5, 5, 5, 51)),
            namedBytes(names, .8f, null).apply { ByteBuffer.wrap(this).putFloat(12, Float.NaN) },
            before.copyOf().apply { ByteBuffer.wrap(this).putInt(4, 3) },
            before.copyOf().apply { ByteBuffer.wrap(this).putInt(0, 99) },
        )
        val bad = folder.newFile("corrupt.bin")
        invalid.forEach { bytes ->
            bad.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { current.restore(bad) }
            assertEquals(profile, current.profile)
            assertArrayEquals(bytes, bad.readBytes())
            samples.forEach { assertArrayEquals(control.predict(it.bottleneck), current.predict(it.bottleneck), 0f) }
        }
        current.train(samples)
        control.train(samples)
        samples.forEach { assertArrayEquals(control.predict(it.bottleneck), current.predict(it.bottleneck), 0f) }
        assertArrayEquals(before, good.readBytes())
    }

    @Test
    fun failedAtomicReplacementDoesNotAdvertiseUnsavedCategoryNames() {
        val current = SoftmaxHead(2)
        val profile = PersonalizationProfile(names)
        val good = folder.newFile("ready.bin").also { current.save(it, profile) }
        val before = good.readBytes()
        val blocked = folder.newFolder("blocked.bin")
        blocked.resolve("keep.txt").writeText("occupied")
        assertThrows(IllegalStateException::class.java) {
            current.save(blocked, PersonalizationProfile(listOf("A", "B", "C", "D")))
        }
        assertEquals(profile, current.profile)
        assertArrayEquals(before, good.readBytes())
        assertEquals("occupied", blocked.resolve("keep.txt").readText())
    }

    private fun legacyBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeInt(2)
            output.writeInt(CLASS_COUNT)
            listOf(
                floatArrayOf(.2f, -.1f, -.3f, .4f, -.5f, .6f, .7f, -.8f),
                floatArrayOf(.01f, -.02f, .03f, -.04f),
                FloatArray(8) { (it + 1) * .001f },
                FloatArray(8) { (it + 1) * .0001f },
                FloatArray(4) { (it + 1) * .002f },
                FloatArray(4) { (it + 1) * .0002f },
            ).forEach { array -> array.forEach(output::writeFloat) }
            output.writeFloat(.81f)
            output.writeFloat(.998001f)
        }
    }.toByteArray()

    private fun namedBytes(names: List<String>, threshold: Float, counts: List<Int>?): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(2)
                output.write(legacyBytes().drop(4).toByteArray())
                names.forEach(output::writeUTF)
                output.writeFloat(threshold)
                output.writeBoolean(counts != null)
                counts?.forEach(output::writeInt)
            }
        }.toByteArray()
}
