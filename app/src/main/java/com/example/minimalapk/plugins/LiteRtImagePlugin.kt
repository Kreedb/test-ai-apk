package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.examples.fittedImage
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import com.example.minimalapk.litert.imaging.ImagingTasks
import com.example.minimalapk.litert.vision.VisionTasks
import kotlin.math.roundToInt

/** Image results always carry an input snapshot. Only the two SR choices may replace the preview. */
internal class LiteRtImagePlugin(
    private val application: Context,
    private val scene: LiteRtScene,
) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        scene.id, scene.name,
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT, PluginCapability.ACTIONS,
            PluginCapability.REPORT_IMAGE, PluginCapability.GUIDE, PluginCapability.MODEL_ASSETS) +
            if (scene.kind == LiteRtSceneKind.IMAGE) setOf(PluginCapability.POINTER, PluginCapability.REPLACEMENT) else emptySet(),
        replacementPriority = 20,
        actions = setOf("analyze", "reference", "observe", "cancel", "retry"),
    )
    private val assets = LiteRtAssets(application)
    @Volatile private var reference: CameraFrame? = null
    @Volatile private var observeToken: PluginToken? = null
    private var observed = 0
    private var lastFrameTime = 0L
    private val observationGate = LiteRtRequestGate()
    private val observationLock = Any()
    @Volatile private var enabledToken: PluginToken? = null
    private val work = LiteRtWork<LiteRtInstalledModel<SingleImageEngine>>(
        "${scene.id}Model",
        open = { context ->
            assets.open(context, scene) { task, directory, backend ->
                if (scene.kind == LiteRtSceneKind.VISION) VisionTasks.create(task, application, directory, backend)
                else ImagingTasks.create(task, application, directory, backend)
            }
        },
        status = { context, message, error -> publish(context, message, error) },
        ready = { context -> publish(context, "已就绪 · ${LiteRtScenes.explanation(task(context))}") },
    )

    private fun task(context: PluginContext) = LiteRtScenes.task(context.settings, scene)
    private fun actions(context: PluginContext): List<PluginAction> {
        val task = task(context)
        return buildList {
            if (task in LiteRtScenes.superResolutionTasks) {
                add(PluginAction("cancel", "取消处理 / 恢复原图"))
            } else if (task == "video-action-recognition") {
                add(PluginAction("observe", "开始短片段观察"))
                add(PluginAction("cancel", "停止观察"))
            } else {
                if (task == "image-matching") add(PluginAction("reference", "捕获参考画面"))
                add(PluginAction("analyze", "分析当前画面"))
                add(PluginAction("cancel", "取消分析"))
            }
            add(PluginAction("retry", "重新加载模型"))
        }
    }

    private fun decorate(context: PluginContext, output: PluginOutput): PluginOutput = output.copy(
        actions = actions(context),
        guide = when (task(context)) {
            "head-pose-estimation" -> PluginGuide(LiteRtScenes.faceRoi, "将一张脸放入黄色框；仅分析框内区域")
            "face-liveness-anti-spoofing" -> PluginGuide(LiteRtScenes.faceRoi,
                "脸放入青框；黄色框为模型的 2.7× 上下文", LiteRtScenes.livenessFaceRoi)
            else -> null
        },
    )

    private fun publish(context: PluginContext, message: String, error: Boolean = false) {
        val detail = if (task(context) in LiteRtScenes.superResolutionTasks &&
            context.settings.config["replace_preview"] == "false") "$message；替换画面已关闭，请在配置中开启" else message
        context.publish(decorate(context, PluginOutput(System.nanoTime(),
            listOf(PluginMessage(detail, if (error) MessageKind.ERROR else MessageKind.STATUS)))))
    }

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> {
                enabledToken = context.token
                reference = null
                observationGate.next()
                observeToken = null
                work.start(context, deferLoad = true)
                publish(context, "已启用 · ${LiteRtScenes.explanation(task(context))}")
            }
            is PluginEvent.Stopped -> {
                if (enabledToken != context.token) return
                enabledToken = null
                observationGate.next(); reference = null; observeToken = null; work.stop()
            }
            is PluginEvent.Action -> when (event.id) {
                "retry" -> { observationGate.next(); observeToken = null; work.start(context) }
                "cancel" -> {
                    observeToken = null
                    observationGate.next()
                    work.cancel()
                    if (task(context) == "video-action-recognition") work.start(context, deferLoad = true)
                    publish(context, "已取消；实时原图持续显示")
                }
                "reference" -> {
                    work.cancel()
                    reference = event.frame
                    val captured = event.frame
                    if (captured == null) publish(context, "等待第一帧")
                    else context.publish(decorate(context, PluginOutput(System.nanoTime(),
                        listOf(PluginMessage("参考帧已捕获；移动相机后分析当前画面", MessageKind.STATUS)),
                        imageReport = PluginImageReport(captured.source, captured.image, label = "参考帧 ${captured.source.frameNumber}"))))
                }
                "observe" -> {
                    observationGate.next()
                    work.start(context) // Reset recurrent state for each observation, on its owner thread.
                    synchronized(observationLock) {
                        observed = 0
                        lastFrameTime = 0
                        observeToken = context.token
                    }
                    publish(context, "短片段观察中：最多 30 帧，每秒最多 5 帧")
                }
                "analyze" -> event.frame?.let { analyze(context, it) } ?: publish(context, "等待第一帧")
            }
            is PluginEvent.Frame -> {
                if (!work.isReady(context)) return
                val observation = synchronized(observationLock) {
                    if (observeToken != context.token || event.frame.timestampNanos - lastFrameTime < 200_000_000L) null
                    else {
                        lastFrameTime = event.frame.timestampNanos
                        observed++
                        if (observed >= 30) observeToken = null
                        observationGate.current()
                    }
                }
                if (observation != null) analyze(context, event.frame, replace = false, observation = observation)
            }
            is PluginEvent.Press -> if (task(context) in LiteRtScenes.superResolutionTasks &&
                context.settings.config["replace_preview"] != "false") {
                context.currentFrame()?.let { analyze(context, it, event.token) }
            }
            is PluginEvent.Release, is PluginEvent.Cancel -> if (task(context) in LiteRtScenes.superResolutionTasks) {
                if (enabledToken != context.token) return
                work.cancel()
                publish(context, "已恢复实时原图；按住预览可重新处理")
            }
            else -> Unit
        }
    }

    private fun analyze(context: PluginContext, frame: CameraFrame, press: PressToken? = null, replace: Boolean = true,
        observation: Long? = null) {
        val task = task(context)
        val second = reference
        if (task == "image-matching" && second == null) { publish(context, "请先捕获参考画面"); return }
        if (task in LiteRtScenes.superResolutionTasks && press == null) return
        if (replace) work.cancel()
        val requestValid = { (press == null || context.currentPress() == press) &&
            (observation == null || observationGate.accepts(observation)) }
        if (replace) work.publishIf(requestValid, context, decorate(context, PluginOutput(System.nanoTime(), listOf(PluginMessage(
            if (press == null) "正在分析来源帧 ${frame.source.frameNumber}…"
            else "正在增强来源快照；请保持按住，松手立即恢复原图", MessageKind.STATUS)))))
        work.submit(context, replace, valid = requestValid) { engine, valid ->
            if (!valid()) return@submit
            val full = liteRtPhoto(frame, if (press != null) 256 else 960)
            val crop = if (task in LiteRtScenes.faceTasks) liteRtCrop(full, LiteRtScenes.faceRoi) else full
            val referenceImage = if (task == "image-matching") second?.let { liteRtPhoto(it, 960) } else null
            var engineOutput: Bitmap? = null
            var reportInput: Bitmap? = null
            var reportResult: Bitmap? = null
            var composite: Bitmap? = null
            var accepted = false
            // These request bitmaps are independent. Engines may return aliases; published copies
            // below are made on the owner thread before another run or close can reuse output pixels.
            try {
                val request = when {
                    task in LiteRtScenes.faceTasks -> ImageTaskRequest(full, crop)
                    task == "image-matching" -> ImageTaskRequest(checkNotNull(referenceImage), full)
                    else -> ImageTaskRequest(full)
                }
                val result = engine.engine.run(request)
                engineOutput = result.bitmap
                if (!valid()) return@submit
                val owned = result.bitmap?.let { checkNotNull(it.copy(Bitmap.Config.ARGB_8888, false)) }
                reportResult = owned
                val input = checkNotNull(crop.copy(Bitmap.Config.ARGB_8888, false))
                reportInput = input
                val report = PluginImageReport(frame.source, BitmapImage(input), owned?.let(::BitmapImage),
                    when {
                        crop !== full -> "来源帧 ${frame.source.frameNumber} · 中央人脸 ROI"
                        task == "image-matching" -> "来源帧 ${frame.source.frameNumber} · 参考帧 ${second?.source?.frameNumber}"
                        else -> "来源帧 ${frame.source.frameNumber}"
                    })
                val messages = buildList {
                    add(PluginMessage(liteRtTiming(result.inferenceMs, result.backend), MessageKind.STATUS))
                    if (task == "video-action-recognition") add(PluginMessage(
                        "实际推理 ${result.metrics["streamFrames"] ?: "—"} 帧；${if (observeToken == context.token) "片段采样中" else "采样已结束"}，可能跳过相机帧", MessageKind.STATUS))
                    addAll(result.text.chunked(PluginHost.MAX_MESSAGE_CHARS).take(4).map { PluginMessage(it) })
                    if (result.backendDetails.isNotBlank()) add(PluginMessage(result.backendDetails, MessageKind.STATUS))
                    if (result.details.isNotBlank()) add(PluginMessage(result.details, MessageKind.STATUS))
                    result.fallbackReason?.let { add(PluginMessage("CPU 回退：$it", MessageKind.STATUS)) }
                    if (press != null) add(PluginMessage("整幅快照 · 工作输入 ${full.width}×${full.height} · 松手恢复实时画面", MessageKind.STATUS))
                }
                val replacement = if (press != null && owned != null) {
                    composite = liteRtComposite(frame, owned)
                    PreviewReplacement(frame.source, BitmapImage(checkNotNull(composite)), press)
                } else null
                accepted = work.publishIf(valid, context, decorate(context, PluginOutput(
                    System.nanoTime(), messages, replacement = replacement, imageReport = report,
                    execution = PluginExecution(result.inferenceMs, result.backend),
                )))
            } finally {
                // Both task factories return caller-owned outputs. Never recycle an alias twice,
                // and never recycle pixels already borrowed by Compose through the host.
                engineOutput?.takeUnless { it === full || it === crop || it === referenceImage }?.recycle()
                if (!accepted) { reportInput?.recycle(); reportResult?.recycle(); composite?.recycle() }
                if (crop !== full) crop.recycle()
                full.recycle()
                referenceImage?.recycle()
            }
        }
    }

    override fun close() { reference = null; observeToken = null; work.close() }
}

internal fun liteRtPhoto(frame: CameraFrame, maxEdge: Int): Bitmap {
    val swapped = frame.source.rotationDegrees % 180 != 0
    val width = if (swapped) frame.raw.height else frame.raw.width
    val height = if (swapped) frame.raw.width else frame.raw.height
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(width, height))
    return fittedImage(frame.raw.bitmap, (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1), frame.source.rotationDegrees).bitmap
}

internal fun liteRtCropBounds(width: Int, height: Int, roi: PhotoRoi): IntArray {
    require(width > 0 && height > 0)
    val left = (width * roi.left).roundToInt().coerceIn(0, width - 1)
    val top = (height * roi.top).roundToInt().coerceIn(0, height - 1)
    return intArrayOf(left, top, (width * roi.right).roundToInt().coerceIn(left + 1, width),
        (height * roi.bottom).roundToInt().coerceIn(top + 1, height))
}

private fun liteRtCrop(image: Bitmap, roi: PhotoRoi): Bitmap {
    val bounds = liteRtCropBounds(image.width, image.height, roi)
    return Bitmap.createBitmap(image, bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1])
}

/** A whole-photo result is placed back inside its own source letterbox, never the latest frame. */
private fun liteRtComposite(frame: CameraFrame, result: Bitmap): Bitmap {
    val space = frame.source.space
    val output = Bitmap.createBitmap(space.width * 2, space.height * 2, Bitmap.Config.ARGB_8888)
    val bounds = space.photoBounds
    Canvas(output).apply {
        drawBitmap(frame.image.bitmap, null, RectF(0f, 0f, output.width.toFloat(), output.height.toFloat()), null)
        drawBitmap(result, null, RectF(bounds.left * 2, bounds.top * 2, bounds.right * 2, bounds.bottom * 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return output
}
