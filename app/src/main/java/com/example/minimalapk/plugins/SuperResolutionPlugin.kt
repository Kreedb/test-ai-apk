package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.SuperResolutionRunner
import com.example.minimalapk.log.AppLog
import java.util.concurrent.CancellationException

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
 * Press-to-compare ESRGAN plugin. A press copies the current source frame once, enhances only the
 * centered half-size square ROI, and publishes that whole-frame composite with the active press
 * token. The live camera frame keeps streaming; release/cancel drops the replacement in the host.
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

    private val model = AsyncModelSlot<ModelRequest, SuperResolutionRunner>(
        name = "SuperResolutionOwner",
        open = { request ->
            if (!request.context.isCurrent()) throw CancellationException("超分辨率请求已失效")
            val file = ExampleDownload.ensure(application, ExampleCatalog.superResolution)
            if (!request.context.isCurrent()) throw CancellationException("超分辨率请求已失效")
            SuperResolutionRunner(file)
        },
        ready = { request, _ ->
            request.context.publish(PluginOutput(
                System.nanoTime(), listOf(PluginMessage("超分辨率模型已就绪", MessageKind.STATUS)),
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
            is PluginEvent.Stopped -> stop()
            is PluginEvent.Frame -> onFrame(event.frame, context)
            is PluginEvent.Press -> startPress(event, context)
            is PluginEvent.Release -> endPress(event.token)
            is PluginEvent.Cancel -> endPress(event.token)
            else -> Unit
        }
    }

    private fun restart(context: PluginContext) {
        if (!context.isCurrent()) return
        val settings = SuperResolutionSettings.from(context.settings)
        synchronized(lock) {
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

    private fun stop() {
        synchronized(lock) {
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
        latestFrame = frame
        val request = synchronized(lock) {
            val press = pendingPress
            val pending = pendingContext
            if (press != null && pending != null) {
                pendingPress = null
                pendingContext = null
                EnhanceRequest(pending, press, frame)
            } else null
        }
        if (request != null) worker.offer(request)
    }

    private fun endPress(token: PressToken) {
        synchronized(lock) {
            if (activePress == token) activePress = null
            if (pendingPress == token) {
                pendingPress = null
                pendingContext = null
            }
        }
        worker.clear()
    }

    private fun isStill(request: EnhanceRequest): Boolean =
        request.context.isCurrent() &&
            request.context.currentPress() == request.press &&
            synchronized(lock) { activePress == request.press }

    private fun enhance(request: EnhanceRequest) {
        if (!isStill(request)) return
        val frame = request.frame
        val roi = try {
            superResolutionRoi(frame.source, frame.raw.width, frame.raw.height)
        } catch (error: Throwable) {
            publishEnhanceFailure(request, error)
            return
        }
        val base = frame.image.copyBitmap()
        var published = false
        try {
            val upscaled = model.runIfReady { _, runner ->
                if (!isStill(request)) return@runIfReady null
                val crop = Bitmap.createBitmap(
                    frame.raw.bitmap, roi.raw.left, roi.raw.top, roi.raw.width, roi.raw.height,
                )
                // The raw crop is in sensor orientation; rotate it upright before ESRGAN so the
                // paste-back ROI is not sideways at 90/270.
                val rotation = ((frame.source.rotationDegrees % 360) + 360) % 360
                val input = if (rotation == 0) crop else Bitmap.createBitmap(
                    crop, 0, 0, crop.width, crop.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true,
                )
                try {
                    runner.run(input)
                } finally {
                    if (input !== crop) input.recycle()
                    if (crop !== frame.raw.bitmap) crop.recycle()
                }
            } ?: return
            try {
                if (!isStill(request)) return
                Canvas(base).drawBitmap(
                    upscaled,
                    Rect(0, 0, upscaled.width, upscaled.height),
                    RectF(
                        roi.composite.left.toFloat(), roi.composite.top.toFloat(),
                        roi.composite.right.toFloat(), roi.composite.bottom.toFloat(),
                    ),
                    paint,
                )
            } finally {
                upscaled.recycle()
            }
            if (!isStill(request)) return
            published = request.context.publish(PluginOutput(
                timestampNanos = System.nanoTime(),
                messages = listOf(
                    PluginMessage("按压：仅增强中心 50% 区域（模型 4×），松手恢复实时原图", MessageKind.STATUS),
                ),
                replacement = PreviewReplacement(
                    source = frame.source,
                    image = BitmapImage(base),
                    press = request.press,
                ),
            ))
        } catch (error: Throwable) {
            publishEnhanceFailure(request, error)
        } finally {
            // Published images are GC-owned; only recycle a composite the host rejected/dropped.
            if (!published) base.recycle()
        }
    }

    private fun publishEnhanceFailure(request: EnhanceRequest, error: Throwable) {
        if (!isStill(request)) return
        val message = error.message ?: "推理失败"
        AppLog.w("SuperResolution", "超分辨率推理失败", error)
        request.context.publish(PluginOutput(
            System.nanoTime(),
            listOf(PluginMessage("超分辨率失败：${message.take(200)}", MessageKind.ERROR)),
        ))
    }

    override fun close() {
        worker.close()
        model.close()
    }
}
