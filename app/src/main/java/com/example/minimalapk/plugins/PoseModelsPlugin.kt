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
    private data class Request(val context: PluginContext)
    private val lock = Any()
    private var current: PluginContext? = null
    private var status: PluginMessage? = null
    private val model = AsyncModelSlot<Request, PoseAnalyzer>(
        "PoseModelOwner",
        open = { request ->
            val config = request.context.settings.config
            PoseAnalyzer.open(
                application, preferenceFromStorage(config["backend"]),
                ModelStore.decode(config.getValue("models")),
                stillNeeded = request.context::isCurrent,
            )
        },
        ready = { request, _ -> synchronized(lock) {
            if (request.context.isCurrent() && current?.token == request.context.token) status = null
        } },
        failed = { request, error -> synchronized(lock) {
            if (request.context.isCurrent() && current?.token == request.context.token) {
                status = PluginMessage(error.message ?: "姿态模型失败", MessageKind.ERROR)
                request.context.publish(PluginOutput(System.nanoTime(), listOf(checkNotNull(status))))
            }
        } },
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> synchronized(lock) {
                if (!context.isCurrent()) return
                current = context
                val enabled = ModelStore.decode(context.settings.config.getValue("models")).any { it.enabled }
                status = PluginMessage(
                    if (enabled) "正在加载姿态模型，基础预览仍可用" else "未启用姿态模型，可在 设置 → 模型 中开启",
                    MessageKind.STATUS,
                )
                model.set(if (enabled) Request(context) else null)
                context.publish(PluginOutput(System.nanoTime(), listOf(checkNotNull(status))))
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (current?.token == context.token) {
                    current = null
                    status = null
                    model.set(null)
                }
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

    fun analyze(source: Bitmap, rotationDegrees: Int): AnalyzedPose? = model.runIfReady { request, analyzer ->
        if (!request.context.isCurrent()) return@runIfReady null
        val result = analyzer.analyze(source, rotationDegrees)
        if (request.context.isCurrent()) AnalyzedPose(result, request.context.token, analyzer.executionProvider) else null
    }

    override fun close() = model.close()
}
