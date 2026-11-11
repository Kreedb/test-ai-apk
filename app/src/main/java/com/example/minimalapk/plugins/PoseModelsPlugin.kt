package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseAnalyzer
import com.example.minimalapk.pose.PoseFrame
import com.example.minimalapk.pose.preferenceFromStorage

internal data class AnalyzedPose(val frame: PoseFrame, val token: PluginToken, val provider: String)

/** Compatibility adapter for 设置 → 模型. The skeleton remains the camera's base layer.
 * Exactly one shared PoseAnalyzer supplies both the skeleton and enhancement measurements.
 */
internal class PoseModelsPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.POSE_MODELS, "姿态模型", setOf(PluginCapability.POSE_RESULTS, PluginCapability.TEXT),
    )

    /** The compiled analyzer depends only on these settings, so it survives a pause/resume. */
    private data class ModelConfig(val backend: String, val models: String)

    private val lock = Any()
    private var current: PluginContext? = null
    private var status: PluginMessage? = null
    private var requested: ModelConfig? = null
    private val model = AsyncModelSlot<ModelConfig, PoseAnalyzer>(
        "PoseModelOwner",
        open = { config ->
            PoseAnalyzer.open(
                application, preferenceFromStorage(config.backend),
                ModelStore.decode(config.models),
                stillNeeded = { synchronized(lock) { requested == config } },
            )
        },
        ready = { config, _ -> synchronized(lock) {
            if (requested == config) status = null
        } },
        failed = { config, error -> synchronized(lock) {
            if (requested == config) {
                status = PluginMessage(error.message ?: "姿态模型失败", MessageKind.ERROR)
                current?.takeIf { it.isCurrent() }
                    ?.publish(PluginOutput(System.nanoTime(), listOf(checkNotNull(status))))
            }
        } },
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> synchronized(lock) {
                if (!context.isCurrent()) return
                current = context
                val config = ModelConfig(
                    context.settings.config["backend"].orEmpty(),
                    context.settings.config.getValue("models"),
                )
                when {
                    ModelStore.decode(config.models).none { it.enabled } -> {
                        requested = null
                        status = PluginMessage("未启用姿态模型，可在 设置 → 模型 中开启", MessageKind.STATUS)
                        model.set(null)
                    }
                    // Background keeps the compiled analyzer; only a config change reloads it.
                    // A failed config stays failed until the user changes it.
                    model.isReady(config) -> status = null
                    requested != config -> {
                        requested = config
                        status = PluginMessage("正在加载姿态模型，基础预览仍可用", MessageKind.STATUS)
                        model.set(config)
                    }
                }
                status?.let { context.publish(PluginOutput(System.nanoTime(), listOf(it))) }
            }
            is PluginEvent.Stopped -> if (event.reason != CameraLifecycle.BACKGROUND) synchronized(lock) {
                current = null
                requested = null
                status = null
                model.set(null)
            }
            is PluginEvent.Pose -> {
                val messages = buildList {
                    synchronized(lock) { status }?.let { add(it) }
                    // Secondary models report under their actual consumer, never under enhancement.
                    event.frame.poses.drop(1).forEach { result ->
                        add(PluginMessage("${result.spec.name}：" +
                            (result.detection?.let { "置信度 ${formatPose(it.score)}" } ?: "未检测到")))
                    }
                }
                context.publish(PluginOutput(event.frame.timestampNanos, messages))
            }
            else -> Unit
        }
    }

    fun analyze(source: Bitmap, rotationDegrees: Int): AnalyzedPose? {
        val context = synchronized(lock) { current }?.takeIf { it.isCurrent() } ?: return null
        return model.runIfReady { _, analyzer ->
            val result = analyzer.analyze(source, rotationDegrees)
            if (context.isCurrent()) AnalyzedPose(result, context.token, analyzer.executionProvider) else null
        }
    }

    override fun close() = model.close()
}
