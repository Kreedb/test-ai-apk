package com.example.minimalapk.examples

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.minimalapk.models.AssetTransfer
import com.example.minimalapk.models.sha256
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class ExampleDownloadCancellationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun verifiedCacheIsUsableWithoutReadingMirrorPreferences() {
        val bytes = ByteArray(32) { it.toByte() }
        val model = ExampleModel("cached", "Cached", "https://example.com/model", bytes.size.toLong(), bytes.sha256())
        val target = File(folder.newFolder("examples"), "cached.tflite").apply { writeBytes(bytes) }
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = folder.root
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                error("A verified cache must not resolve download preferences")
        }
        assertEquals(target, ExampleDownload.ensure(context, model))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun cancelledLoadNeverTouchesCacheOrPreferences() {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = error("Cancelled request cannot access storage")
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = error("Cancelled request cannot read settings")
        }
        val transfer = AssetTransfer().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            ExampleDownload.install(context, ExampleCatalog.audio, transfer)
        }
    }
}
