package com.example.minimalapk.plugins

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.pose.PoseAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The sole CameraX owner. Model/config changes do not rebind the use case. Main-thread close
 * unbinds synchronously; background owners drain their current work without interrupting it.
 */
internal class CameraSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val host: PluginHost,
    private val poses: PoseModelsPlugin,
    private val audio: AudioCaptureHost? = null,
) : AutoCloseable {
    private val application = context.applicationContext
    private val main = ContextCompat.getMainExecutor(application)
    private val analysisWorker = Executors.newSingleThreadExecutor { Thread(it, "CameraAnalysis") }
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var started = false
    @Volatile private var closed = false
    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    private val frames = AtomicLong()
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> {
                audio?.onBackground()
                host.lifecycle(CameraLifecycle.BACKGROUND)
            }
            Lifecycle.Event.ON_STOP -> {
                audio?.onBackground()
                host.lifecycle(CameraLifecycle.STOP)
            }
            Lifecycle.Event.ON_START -> host.lifecycle(CameraLifecycle.ENTER)
            else -> Unit
        }
    }

    private fun resume() {
        // Permission UI also rechecks on resume; do not briefly restart consumers before it runs.
        if (ContextCompat.checkSelfPermission(application, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            host.lifecycle(CameraLifecycle.FOREGROUND)
            audio?.onForeground()
        }
    }

    fun start() {
        if (started || closed) return
        started = true
        host.lifecycle(CameraLifecycle.ENTER)
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resume()
        }
        val future = ProcessCameraProvider.getInstance(application)
        future.addListener({
            // Provider futures cannot reliably be cancelled. Never bind after this screen leaves.
            if (closed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    ).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis = useCase
                useCase.setAnalyzer(analysisWorker) { proxy ->
                    val session = host.sessionId()
                    if (closed || session == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val number = frames.incrementAndGet()
                    // System.nanoTime is also used by press and audio events (one monotonic clock).
                    val timestamp = System.nanoTime()
                    try {
                        val raw = proxy.toBitmap()
                        val rotation = proxy.imageInfo.rotationDegrees
                        val analyzed = poses.analyze(raw, rotation)
                        val result = analyzed?.frame ?: PoseAnalyzer.preview(raw, rotation)
                        val space = ImageSpace(result.bitmap.width, result.bitmap.height, result.photoBounds)
                        val frame = CameraFrame(
                            source = ImageSource(session, number, space, rotation),
                            timestampNanos = timestamp,
                            raw = BitmapImage(raw), image = BitmapImage(result.bitmap),
                            viewport = ViewportMapping(space, viewportWidth, viewportHeight),
                            poses = result.results, poseToken = analyzed?.token,
                            executionProvider = analyzed?.provider,
                        )
                        host.acceptFrame(frame)
                        // Keep proxy ownership until the main executor has consumed this delivery.
                        // This bounds display work as well as CameraX frames. Published bitmaps are
                        // immutable by contract and GC-owned, never recycled under Compose/async work.
                        main.execute {
                            try { if (!closed && host.sessionId() == session) mutableError.value = null }
                            finally { proxy.close() }
                        }
                    } catch (_: CancellationException) {
                        main.execute { proxy.close() }
                    } catch (error: Exception) {
                        main.execute {
                            try {
                                if (!closed && host.sessionId() == session) {
                                    mutableError.value = error.message ?: "相机帧读取失败"
                                    AppLog.e("Camera", "相机帧读取失败", error)
                                }
                            } finally { proxy.close() }
                        }
                    }
                }
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
            } catch (error: Exception) {
                mutableError.value = error.message ?: "相机初始化失败"
                AppLog.e("Camera", "相机初始化失败", error)
            }
        }, main)
    }

    fun viewport(width: Int, height: Int) {
        if (viewportWidth == width && viewportHeight == height) return
        viewportWidth = width
        viewportHeight = height
        host.viewport(width, height)
    }

    override fun close() {
        if (closed) return
        closed = true
        audio?.onBackground()
        host.lifecycle(CameraLifecycle.LEAVE)
        lifecycleOwner.lifecycle.removeObserver(observer)
        try {
            analysis?.clearAnalyzer()
            analysis?.let { provider?.unbind(it) }
        } finally {
            analysisWorker.shutdown()
            host.close()
        }
    }
}
