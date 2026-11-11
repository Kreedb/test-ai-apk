package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.ModelStore
import com.example.minimalapk.pose.PoseAnalyzer
import com.example.minimalapk.pose.PoseFrame
import com.example.minimalapk.pose.preferenceFromStorage
import com.example.minimalapk.pose.TfliteGraph
import com.example.minimalapk.pose.modelFile
import com.example.minimalapk.pose.validatePoseModelConfig
import java.util.concurrent.CancellationException

internal data class AnalyzedPose(val frame: PoseFrame, val token: PluginToken, val provider: String)

/** Compatibility adapter for the pose model list in 插件 → 自定义模型. The skeleton remains the camera's base layer.
 * Exactly one shared PoseAnalyzer supplies both the skeleton and enhancement measurements.
 */
internal class PoseModelsPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.POSE_MODELS, "姿态模型", setOf(PluginCapability.POSE_RESULTS, PluginCapability.TEXT),
    )
    private data class Request(val context: PluginContext)
    private class Loaded(val analyzer: PoseAnalyzer, val problems: Map<String, PluginMessage>) : AutoCloseable {
        override fun close() = analyzer.close()
    }
    private val lock = Any()
    private var current: PluginContext? = null
    private var status: PluginMessage? = null
    private var problems: Map<String, PluginMessage> = emptyMap()
    private val model = AsyncModelSlot<Request, Loaded>(
        "PoseModelOwner",
        open = { request ->
            val config = request.context.settings.config
            val specs = ModelStore.decode(config.getValue("models"))
            ModelStore.requireEnabledCapacity(specs)
            val invalid = mutableMapOf<String, PluginMessage>()
            specs.filter { it.enabled }.forEach { spec ->
                if (!request.context.isCurrent()) throw CancellationException("Pose request superseded")
                try {
                    val bytes = spec.assetPath?.let { application.assets.open(it).use { stream -> stream.readBytes() } }
                        ?: modelFile(application, spec).readBytes()
                    validatePoseModelConfig(spec, TfliteGraph(bytes).describe())
                } catch (error: Exception) {
                    AppLog.e("PoseModels", "${spec.name} 配置或模型校验失败", error)
                    invalid[spec.id] = PluginMessage("${spec.name}：${error.message}", MessageKind.ERROR)
                }
            }
            Loaded(PoseAnalyzer.open(
                application, preferenceFromStorage(config["backend"]),
                specs.map { if (it.id in invalid) it.copy(enabled = false) else it },
                stillNeeded = request.context::isCurrent,
            ), invalid)
        },
        ready = { request, loaded -> synchronized(lock) {
            if (request.context.isCurrent() && current?.token == request.context.token) {
                status = null
                problems = loaded.problems
                request.context.publish(PluginOutput(System.nanoTime(), problems.values.toList()))
            }
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
                problems = emptyMap()
                val enabled = ModelStore.decode(context.settings.config.getValue("models")).any { it.enabled }
                status = if (enabled) PluginMessage("正在加载姿态模型，基础预览仍可用", MessageKind.STATUS) else null
                model.set(if (enabled) Request(context) else null)
                context.publish(PluginOutput(System.nanoTime(), listOfNotNull(status)))
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (current?.token == context.token) {
                    current = null
                    status = null
                    problems = emptyMap()
                    model.set(null)
                }
            }
            is PluginEvent.Pose -> {
                val (currentStatus, invalid) = synchronized(lock) { status to problems }
                val messages = buildList {
                    currentStatus?.let { add(it) }
                    addAll(invalid.values)
                    // Secondary models report under their actual consumer, never under enhancement.
                    event.frame.poses.drop(1).forEach { result ->
                        add(PluginMessage("${result.spec.name}：" +
                            (result.detection?.let { "置信度 ${formatPose(it.score)}" } ?: "未检测到")))
                    }
                    if (currentStatus == null && event.frame.poseToken == context.token) {
                        ModelStore.decode(context.settings.config.getValue("models"))
                            .filter { spec -> spec.enabled && spec.id !in invalid && event.frame.poses.none { it.spec.id == spec.id } }
                            .forEach { add(PluginMessage("${it.name}：加载未成功，请检查模型配置与日志", MessageKind.ERROR)) }
                    }
                }
                context.publish(PluginOutput(event.frame.timestampNanos, messages))
            }
            else -> Unit
        }
    }

    fun analyze(source: Bitmap, rotationDegrees: Int): AnalyzedPose? = model.runIfReady { request, loaded ->
        if (!request.context.isCurrent()) return@runIfReady null
        val result = loaded.analyzer.analyze(source, rotationDegrees)
        if (request.context.isCurrent()) AnalyzedPose(result, request.context.token, loaded.analyzer.executionProvider) else null
    }

    override fun close() = model.close()
}
