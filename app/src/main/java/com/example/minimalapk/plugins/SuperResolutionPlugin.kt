package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SuperResolutionRunner
import com.example.minimalapk.examples.fittedImage
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.AssetTransfer
import java.util.concurrent.CancellationException
import java.util.Locale

internal data class SuperResolutionSettings(
    val replacePreview: Boolean = true,
) {
    fun toConfig(): Map<String, String> = mapOf("replace_preview" to replacePreview.toString())

    companion object {
        fun from(settings: PluginSettings): SuperResolutionSettings = SuperResolutionSettings(
            // Pressing is explicit and the replacement exists only while held, so it defaults on.
            replacePreview = settings.config["replace_preview"]?.toBooleanStrictOrNull() ?: true,
        )
    }
}

/**
 * One whole-photo snapshot per press. Overlapping fixed-shape inferences have bounded pixel/call
 * budgets; the native model is never resized to a camera-sized tensor. Release cancels between tiles.
 */
internal class SuperResolutionPlugin(private val application: Context) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.SUPER_RESOLUTION, "超分辨率",
        setOf(
            PluginCapability.CAMERA_FRAMES, PluginCapability.POINTER,
            PluginCapability.REPLACEMENT, PluginCapability.TEXT,
        ),
        replacementPriority = 10,
    )

    private data class ModelRequest(val context: PluginContext)
    private data class EnhanceRequest(
        val context: PluginContext,
        val press: PressToken,
        val frame: CameraFrame,
    )

    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    @Volatile private var latestFrame: CameraFrame? = null
    private var activePress: PressToken? = null
    private var pendingPress: PressToken? = null
    private var pendingContext: PluginContext? = null
    private var current: PluginContext? = null

    private val model = AsyncModelSlot<ModelRequest, SuperResolutionRunner>(
        name = "SuperResolutionOwner",
        open = { request ->
            val transfer = AssetTransfer {
                if (!request.context.isCurrent()) throw CancellationException("超分辨率请求已失效")
            }
            transfer.checkpoint()
            val file = ExampleDownload.ensure(application, ExampleCatalog.superResolution, transfer)
            transfer.checkpoint()
            SuperResolutionRunner(file)
        },
        ready = { request, _ ->
            request.context.publish(PluginOutput(
                System.nanoTime(), listOf(PluginMessage("已就绪：按住预览处理整张照片，完成后显示；松手恢复实时原图", MessageKind.STATUS)),
            ))
        },
        failed = { request, error ->
            if (error !is CancellationException) {
                val message = error.message ?: "超分辨率模型加载失败"
                if (request.context.isCurrent()) {
                    request.context.publish(PluginOutput(
                        System.nanoTime(),
                        listOf(PluginMessage("未就绪：${message.take(200)}", MessageKind.ERROR)),
                    ))
                }
            }
        },
    )

    private val worker = BoundedFrameWorker<EnhanceRequest>(
        name = "SuperResolutionWork",
        failed = { request, error -> publishEnhanceFailure(request, error) },
        consume = ::enhance,
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> restart(context)
            is PluginEvent.Stopped -> stop(context)
            is PluginEvent.Frame -> onFrame(event.frame, context)
            is PluginEvent.Press -> startPress(event, context)
            is PluginEvent.Release -> endPress(event.token, context)
            is PluginEvent.Cancel -> endPress(event.token, context)
            else -> Unit
        }
    }

    private fun restart(context: PluginContext) {
        if (!context.isCurrent()) return
        val settings = SuperResolutionSettings.from(context.settings)
        synchronized(lock) {
            if (!context.isCurrent()) return
            current = context
            latestFrame = null
            activePress = null
            pendingPress = null
            pendingContext = null
        }
        worker.clear()
        if (!settings.replacePreview) {
            model.set(null)
            context.publish(PluginOutput(
                System.nanoTime(),
                listOf(PluginMessage("替换画面已关闭；按住预览不会增强", MessageKind.STATUS)),
            ))
            return
        }
        context.publish(PluginOutput(
            System.nanoTime(),
            listOf(PluginMessage("未就绪：正在下载/加载超分辨率模型（约 5 MB）", MessageKind.STATUS)),
        ))
        model.set(ModelRequest(context))
    }

    private fun stop(context: PluginContext) {
        synchronized(lock) {
            if (current?.token != context.token) return
            current = null
            latestFrame = null
            activePress = null
            pendingPress = null
            pendingContext = null
        }
        worker.clear()
        // Release the interpreter on background/disable; a new Started reopens from the cached file.
        model.set(null)
    }

    private fun startPress(event: PluginEvent.Press, context: PluginContext) {
        if (!SuperResolutionSettings.from(context.settings).replacePreview) return
        if (!context.isCurrent()) return
        val request = synchronized(lock) {
            activePress = event.token
            pendingPress = null
            pendingContext = null
            val frame = latestFrame
            if (frame != null && frame.source.session == event.token.session &&
                frame.source.frameNumber >= event.token.firstFrame
            ) {
                EnhanceRequest(context, event.token, frame)
            } else {
                // Press can race the analysis worker between accepting a frame and delivering it.
                // Wait for the next frame so the snapshot cannot precede the press's firstFrame.
                pendingPress = event.token
                pendingContext = context
                null
            }
        }
        if (request != null) worker.offer(request)
    }

    private fun onFrame(frame: CameraFrame, context: PluginContext) {
        if (!context.isCurrent()) return
        val request = synchronized(lock) {
            if (current?.token != context.token || !context.isCurrent()) return
            latestFrame = frame
            val press = pendingPress
            val pending = pendingContext
            if (press != null && pending != null && activePress == press &&
                frame.source.session == press.session && frame.source.frameNumber >= press.firstFrame) {
                pendingPress = null
                pendingContext = null
                EnhanceRequest(pending, press, frame)
            } else null
        }
        if (request != null) worker.offer(request)
    }

    private fun endPress(token: PressToken, context: PluginContext) {
        synchronized(lock) {
            if (activePress != token) return
            activePress = null
            if (pendingPress == token) {
                pendingPress = null
                pendingContext = null
            }
        }
        worker.clear()
        // The host removes replacements synchronously; also retire the old "processing" text.
        if (context.isCurrent()) context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(
            "已恢复实时原图；按住预览可重新处理整张照片", MessageKind.STATUS,
        ))))
    }

    private fun isStill(request: EnhanceRequest): Boolean = superResolutionRequestIsCurrent(
        request.context.isCurrent(), request.press, request.context.currentPress(),
        synchronized(lock) { activePress }, request.frame.source,
    )

    /** Order status updates against release as well as images: a late tile must not restore
     * "processing" after endPress has published "restored". Host independently checks the image. */
    private fun publishForPress(request: EnhanceRequest, output: PluginOutput): Boolean = synchronized(lock) {
        if (!isStill(request)) false else request.context.publish(output)
    }

    private fun enhance(request: EnhanceRequest) {
        if (!isStill(request)) return
        val frame = request.frame
        var composite: Bitmap? = null
        var published = false
        try {
            val enhanced = model.runIfReady { loaded, runner ->
                if (loaded.context.token != request.context.token) return@runIfReady null
                if (!isStill(request)) return@runIfReady null
                val swapped = frame.source.rotationDegrees % 180 != 0
                val plan = superResolutionPlan(
                    if (swapped) frame.raw.height else frame.raw.width,
                    if (swapped) frame.raw.width else frame.raw.height,
                )
                val started = System.nanoTime()
                AppLog.i("SuperResolution", "整帧请求 raw=${frame.raw.width}x${frame.raw.height} " +
                    "rotation=${frame.source.rotationDegrees} working=${plan.width}x${plan.height} " +
                    "tiles=${plan.tiles.size}/${SUPER_RESOLUTION_MAX_TILES} model=50→200 CPU budget=30s " +
                    "session=${frame.source.session} frame=${frame.source.frameNumber}")
                publishForPress(request, PluginOutput(started, listOf(PluginMessage(
                    "整帧处理中：${plan.width}×${plan.height} → ${plan.outputWidth}×${plan.outputHeight}，" +
                        "${plan.tiles.size} 块；请保持按住，松手可取消", MessageKind.STATUS,
                ))))
                val fitted = fittedImage(frame.raw.bitmap, plan.width, plan.height, frame.source.rotationDegrees)
                val input = fitted.bitmap
                val pixels = IntArray(plan.width * plan.height)
                try {
                    input.getPixels(pixels, 0, plan.width, 0, 0, plan.width, plan.height)
                } finally {
                    input.recycle()
                }
                val (width, height) = superResolutionCompositeSize(frame.source.space, plan)
                val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                composite = base
                val canvas = Canvas(base)
                canvas.drawBitmap(frame.image.bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
                val bounds = frame.source.space.photoBounds
                val sx = width.toFloat() / frame.source.space.width
                val sy = height.toFloat() / frame.source.space.height
                val photoWidth = (bounds.right - bounds.left) * sx
                val photoHeight = (bounds.bottom - bounds.top) * sy
                canvas.save()
                canvas.clipRect(bounds.left * sx, bounds.top * sy, bounds.right * sx, bounds.bottom * sy)
                var lastProgress = started
                for ((index, tile) in plan.tiles.withIndex()) {
                    if (!isStill(request)) return@runIfReady null
                    if (System.nanoTime() - started > MAX_ENHANCE_NANOS) {
                        publishEnhanceFailure(request, IllegalStateException("整帧处理超过 30 秒预算，已保留原图；可再次按住重试"))
                        return@runIfReady null
                    }
                    val upscaled = runner.runTile(pixels, plan.width, plan.height, tile.sampleLeft, tile.sampleTop)
                    try {
                        if (!isStill(request)) return@runIfReady null
                        if (System.nanoTime() - started > MAX_ENHANCE_NANOS) {
                            publishEnhanceFailure(request, IllegalStateException("整帧处理超过 30 秒预算，已保留原图；可再次按住重试"))
                            return@runIfReady null
                        }
                        val crop = tile.outputCrop
                        canvas.drawBitmap(
                            upscaled, Rect(crop.left, crop.top, crop.right, crop.bottom),
                            RectF(
                                bounds.left * sx + (tile.core.left - fitted.fit.left) * photoWidth / fitted.fit.photoWidth,
                                bounds.top * sy + (tile.core.top - fitted.fit.top) * photoHeight / fitted.fit.photoHeight,
                                bounds.left * sx + (tile.core.right - fitted.fit.left) * photoWidth / fitted.fit.photoWidth,
                                bounds.top * sy + (tile.core.bottom - fitted.fit.top) * photoHeight / fitted.fit.photoHeight,
                            ), paint,
                        )
                    } finally { upscaled.recycle() }
                    val now = System.nanoTime()
                    if (now - lastProgress >= 1_000_000_000L && isStill(request)) {
                        publishForPress(request, PluginOutput(now, listOf(PluginMessage(
                            "整帧处理中：${index + 1}/${plan.tiles.size} 块，松手取消并恢复实时原图", MessageKind.STATUS,
                        ))))
                        lastProgress = now
                    }
                }
                canvas.restore()
                plan to (System.nanoTime() - started)
            }
            // Loading/error callbacks own the diagnostic when no model was borrowed. Do not mask
            // an actual inference failure (runIfReady closes the model and reports it) as loading.
            if (enhanced == null) return
            if (!isStill(request)) return
            val (plan, elapsed) = enhanced
            val base = checkNotNull(composite)
            published = publishForPress(request, PluginOutput(
                timestampNanos = System.nanoTime(),
                messages = listOf(
                    PluginMessage(
                        "整帧增强快照：工作图 ${plan.width}×${plan.height}，模型 4×，显示 ${base.width}×${base.height}；" +
                            "耗时 ${String.format(Locale.ROOT, "%.1f", elapsed / 1e9)} 秒，松手恢复实时原图", MessageKind.STATUS,
                    ),
                ),
                replacement = PreviewReplacement(
                    source = frame.source,
                    image = BitmapImage(base),
                    press = request.press,
                ),
            ))
            if (published) AppLog.i("SuperResolution", "整帧完成 output=${base.width}x${base.height} " +
                "tiles=${plan.tiles.size} elapsedMs=${elapsed / 1_000_000}; snapshot only, source frame=${frame.source.frameNumber}")
        } catch (error: Throwable) {
            publishEnhanceFailure(request, error)
        } finally {
            // Published images are GC-owned; only recycle a composite the host rejected/dropped.
            if (!published) composite?.recycle()
        }
    }

    private fun publishEnhanceFailure(request: EnhanceRequest, error: Throwable) {
        if (!isStill(request)) return
        val message = error.message ?: "推理失败"
        AppLog.w("SuperResolution", "超分辨率推理失败", error)
        publishForPress(request, PluginOutput(
            System.nanoTime(),
            listOf(PluginMessage("超分辨率失败：${message.take(200)}", MessageKind.ERROR)),
        ))
    }

    override fun close() {
        worker.close()
        model.close()
    }

    private companion object {
        const val MAX_ENHANCE_NANOS = 30_000_000_000L
    }
}
