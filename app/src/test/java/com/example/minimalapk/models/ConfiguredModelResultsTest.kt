package com.example.minimalapk.models

import com.example.minimalapk.plugins.PluginSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ConfiguredModelResultsTest {
    private val model = ConfiguredModel("real-model-hash", "test", File("configured_models/real-model-hash.tflite"), enabled = true)
    private val version = ConfiguredModelVersion(model.id, model.file.absolutePath, "sha256-of-saved-config", true, 4L)
    private val settings = PluginSettings(true, revision = 2L)
    private val preview = ConfiguredResultPreview("42")
    private fun store() = ConfiguredResultStore().apply { configure(settings) }

    @Test
    fun publishingRequiresActualIdentitySavedConfigAndBothEnabledToggles() {
        val store = store()
        assertNull(store.begin(model, version, "unsaved-config"))
        assertNull(store.begin(model.copy(id = "other"), version, version.fingerprint))
        assertNull(store.begin(model, version.copy(path = "other/location"), version.fingerprint))
        assertNull(store.begin(model, version.copy(enabled = false), version.fingerprint))
        store.configure(settings.copy(enabled = false))
        assertNull(store.begin(model, version, version.fingerprint))
        assertNull(store.result.value)
    }

    @Test
    fun latestOneShotIsRetainedForNextCameraStartAndOlderExecutionsCannotReplaceIt() {
        val store = store()
        val old = store.begin(model, version, version.fingerprint)!!
        val latest = store.begin(model, version, version.fingerprint)!!
        assertFalse(store.publish(old, version, preview))
        assertTrue(store.publish(latest, version, preview))
        store.configure(settings) // A new camera consumer observes the same application state.
        assertEquals(latest, store.result.value!!.ticket)
        assertEquals("42", store.result.value!!.preview.text)
        assertFalse(store.publish(latest, version, ConfiguredResultPreview("duplicate")))
        assertEquals("42", store.result.value!!.preview.text)
    }

    @Test
    fun disableAndReenableCannotLeakLateResultsEvenWhenConfigMatches() {
        val store = store()
        val ticket = store.begin(model, version, version.fingerprint)!!
        store.configure(settings.copy(enabled = false, revision = 3L))
        store.configure(settings.copy(revision = 4L))
        assertFalse(store.publish(ticket, version, preview))
        assertNull(store.result.value)
        val next = store.begin(model, version, version.fingerprint)!!
        assertTrue(store.publish(next, version, preview))
        store.configure(settings.copy(enabled = false, revision = 5L))
        assertNull(store.result.value)
    }

    @Test
    fun modelDisableConfigRoundTripDeletionAndMasterReconfigureRejectPendingCompletion() {
        for (changed in listOf(version.copy(enabled = false), version.copy(fingerprint = "changed"),
                version.copy(generation = 6L), null)) {
            val store = store()
            val ticket = store.begin(model, version, version.fingerprint)!!
            assertFalse(store.publish(ticket, changed, preview))
            assertNull(store.result.value)
        }
        val store = store()
        val ticket = store.begin(model, version, version.fingerprint)!!
        store.configure(settings.copy(config = mapOf("option" to "new"), revision = 3L))
        assertFalse(store.publish(ticket, version, preview))
    }

    @Test
    fun modelInvalidationClearsResultAndPendingButUnrelatedModelsDoNot() {
        val store = store()
        val first = store.begin(model, version, version.fingerprint)!!
        store.invalidate("another model")
        assertTrue(store.publish(first, version, preview))
        store.invalidate("another model")
        assertNotNull(store.result.value)
        store.invalidate(version.path)
        assertNull(store.result.value)
        val pending = store.begin(model, version, version.fingerprint)!!
        store.invalidate(version.path)
        assertFalse(store.publish(pending, version, preview))
    }

    @Test
    fun retainedTextIsBoundedAndStartingANewRequestWithdrawsTheOldResult() {
        val store = store()
        val ticket = store.begin(model, version, version.fingerprint)!!
        assertTrue(store.publish(ticket, version, ConfiguredResultPreview("x".repeat(100_000))))
        assertEquals(ConfiguredResultPreview.MAX_TEXT, store.result.value!!.preview.text.length)
        store.begin(model, version, version.fingerprint)
        assertNull(store.result.value)
    }

    @Test
    fun cancellingAnOldDialogCannotWithdrawANewerResultAndBackgroundRejectsLatePublish() {
        val store = store()
        val old = store.begin(model, version, version.fingerprint)!!
        val fresh = store.begin(model, version, version.fingerprint)!!
        store.cancel(old)
        assertTrue(store.publish(fresh, version, preview))
        store.cancel(old)
        assertEquals(fresh, store.result.value!!.ticket)
        store.cancel(fresh)
        assertNull(store.result.value)
        val background = store.begin(model, version, version.fingerprint)!!
        store.cancel(background)
        assertFalse(store.publish(background, version, preview))
    }

    @Test
    fun masterSettingChangeCancelsActiveWorkButAnIdenticalCameraAttachDoesNot() {
        var cancellations = 0
        val store = ConfiguredResultStore { cancellations++ }
        store.configure(settings)
        assertEquals(1, cancellations)
        store.configure(settings)
        assertEquals(1, cancellations)
        store.configure(settings.copy(enabled = false, revision = 3))
        assertEquals(2, cancellations)
    }

    @Test
    fun invalidationCancelsARegisteredRequestBeforeItsNativeRunnerStarts() {
        val store = store()
        val transfer = AssetTransfer()
        val pending = store.begin(model, version, version.fingerprint, transfer::cancel)!!
        store.invalidate(version.path)
        assertThrows(java.util.concurrent.CancellationException::class.java) { transfer.checkpoint() }
        assertFalse(store.publish(pending, version, preview))
        val completed = AssetTransfer()
        val next = store.begin(model, version, version.fingerprint, completed::cancel)!!
        assertTrue(store.publish(next, version, preview))
        store.cancel(next)
        completed.checkpoint() // A finished run no longer owns cancellation resources.
    }
}
