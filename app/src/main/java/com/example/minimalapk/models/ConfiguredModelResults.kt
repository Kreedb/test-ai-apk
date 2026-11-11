package com.example.minimalapk.models

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.example.minimalapk.plugins.PluginIds
import com.example.minimalapk.plugins.PluginSettings
import com.example.minimalapk.plugins.restorePluginSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Includes the enable epoch: off/on with identical properties must not revive an old result. */
internal data class ConfiguredModelVersion(
    val modelId: String,
    val path: String,
    val fingerprint: String,
    val enabled: Boolean,
    val generation: Long,
)

internal data class ConfiguredRunTicket(
    val model: ConfiguredModel,
    val version: ConfiguredModelVersion,
    val pluginRevision: Long,
    val sequence: Long,
)

/** At most two 512px images and 2048 text characters, independently owned from a dialog result. */
internal data class ConfiguredResultPreview(
    val text: String,
    val input: Bitmap? = null,
    val output: Bitmap? = null,
    val label: String = "单次运行",
) {
    companion object {
        const val MAX_TEXT = 2048
        const val MAX_IMAGE_EDGE = 512

        fun from(result: ModelRunResult): ConfiguredResultPreview = ConfiguredResultPreview(
            text = result.text.take(MAX_TEXT),
            input = result.inputImage?.let(::copyImage),
            output = result.images.firstOrNull()?.second?.let(::copyImage),
            label = (result.sourceLabel + if (result.images.size > 1) " · 仅显示首个图像输出" else "").take(100),
        )

        private fun copyImage(image: Bitmap): Bitmap {
            val factor = minOf(1.0, MAX_IMAGE_EDGE.toDouble() / maxOf(image.width, image.height))
            val resized = image.scale(maxOf(1, (image.width * factor).toInt()), maxOf(1, (image.height * factor).toInt()))
            return if (resized !== image) resized else checkNotNull(image.copy(Bitmap.Config.ARGB_8888, false))
        }
    }
}

internal data class ConfiguredPublishedResult(val ticket: ConfiguredRunTicket, val preview: ConfiguredResultPreview)

/** Pure lifecycle seam. One latest request/result across the application, never a request queue. */
internal class ConfiguredResultStore(private val reconfigured: () -> Unit = {}) {
    private var plugin = PluginSettings()
    private var sequence = 0L
    private var pending: ConfiguredRunTicket? = null
    private var cancelPending: (() -> Unit)? = null
    private val mutableResult = MutableStateFlow<ConfiguredPublishedResult?>(null)
    val result = mutableResult.asStateFlow()

    @Synchronized
    fun configure(settings: PluginSettings) {
        if (settings != plugin) {
            plugin = settings
            clear()
            reconfigured()
        }
    }

    @Synchronized
    fun begin(model: ConfiguredModel, version: ConfiguredModelVersion, fingerprint: String,
              onCancel: () -> Unit = {}): ConfiguredRunTicket? {
        if (!plugin.enabled || !version.enabled || version.modelId != model.id ||
            version.path != model.file.absolutePath || version.fingerprint != fingerprint) return null
        clear()
        return ConfiguredRunTicket(model, version, plugin.revision, ++sequence).also { pending = it; cancelPending = onCancel }
    }

    @Synchronized
    fun publish(ticket: ConfiguredRunTicket, version: ConfiguredModelVersion?, preview: ConfiguredResultPreview): Boolean {
        if (pending != ticket || !plugin.enabled || plugin.revision != ticket.pluginRevision ||
            version != ticket.version || !version.enabled) return false
        pending = null
        cancelPending = null
        mutableResult.value = ConfiguredPublishedResult(ticket, preview.copy(text = preview.text.take(ConfiguredResultPreview.MAX_TEXT)))
        return true
    }

    @Synchronized
    fun invalidate(path: String) {
        if (pending?.version?.path == path || mutableResult.value?.ticket?.version?.path == path) clear()
    }

    @Synchronized
    fun cancel(ticket: ConfiguredRunTicket) {
        if (pending == ticket || mutableResult.value?.ticket == ticket) clear()
    }

    @Synchronized
    fun clear() {
        sequence++
        pending = null
        mutableResult.value = null
        val cancel = cancelPending
        cancelPending = null
        cancel?.invoke()
    }
}

/** Process-scoped only: no Uri, microphone samples or result images are persisted. */
internal object ConfiguredModelResults {
    private val store = ConfiguredResultStore(ConfiguredModelAccess::cancelRun)
    val result = store.result
    private var preferences: SharedPreferences? = null
    private var observer: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Synchronized
    fun attach(context: Context) {
        if (preferences == null) {
            val saved = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            preferences = saved
            observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key.startsWith("plugin.${PluginIds.CONFIGURED_MODEL}.")) refreshSettings()
            }.also { saved.registerOnSharedPreferenceChangeListener(it) }
        }
        refreshSettings()
    }

    @Synchronized
    private fun refreshSettings() {
        val saved = preferences ?: return
        store.configure(restorePluginSettings(saved.all,
            mapOf(PluginIds.CONFIGURED_MODEL to PluginSettings())).getValue(PluginIds.CONFIGURED_MODEL))
    }

    fun begin(context: Context, model: ConfiguredModel, savedText: String, transfer: AssetTransfer? = null): ConfiguredRunTicket? {
        attach(context)
        return synchronized(ConfiguredModelStore) {
            val version = ConfiguredModelStore.version(model) ?: return null
            store.begin(model, version, savedText.toByteArray().sha256()) { transfer?.cancel() }
        }
    }

    fun publish(ticket: ConfiguredRunTicket, output: ModelRunResult): Boolean {
        val preview = ConfiguredResultPreview.from(output)
        refreshSettings()
        val published = synchronized(ConfiguredModelStore) {
            store.publish(ticket, ConfiguredModelStore.version(ticket.model), preview)
        }
        if (!published) (listOfNotNull(preview.input, preview.output)).distinct().forEach { it.recycle() }
        return published
    }

    fun invalidate(model: ConfiguredModel) = store.invalidate(model.file.absolutePath)
    fun cancel(ticket: ConfiguredRunTicket) = store.cancel(ticket)
    fun clear() = store.clear()
}
