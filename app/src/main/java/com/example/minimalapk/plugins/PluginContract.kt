package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.pose.ModelResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class PluginCapability {
    CAMERA_FRAMES, POSE_RESULTS, POINTER, LIFECYCLE, AUDIO_PERMISSION, AUDIO_PCM,
    TEXT, LAYER, REPLACEMENT, MONITOR, ACTIONS, REPORT_IMAGE, GUIDE, MODEL_ASSETS,
}

internal data class PluginDescriptor(
    val id: String,
    val name: String,
    val capabilities: Set<PluginCapability>,
    /** Higher wins; ties use the stable ID in ascending order, never completion order. */
    val replacementPriority: Int = 0,
    /** Allowlisted camera controls; labels/enabled state come from the current output. */
    val actions: Set<String> = emptySet(),
    /** Bounded/discovered consumers opt out of capture and permission prompts until publishing intent. */
    val continuousAudio: Boolean = true,
)

/** Borrowed, read-only pixels. Retaining this object retains the bitmap; never mutate/recycle it.
 * A consumer needing writable pixels owns and eventually releases its own copy. */
internal interface PluginImage {
    val width: Int
    val height: Int
    val bitmap: Bitmap
    fun copyBitmap(): Bitmap = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true))
}

internal class BitmapImage(override val bitmap: Bitmap) : PluginImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

internal data class CameraFrame(
    val source: ImageSource,
    val timestampNanos: Long,
    /** Original camera pixels used by both the sharp preview and source-frame super resolution. */
    val raw: PluginImage,
    val image: PluginImage,
    val viewport: ViewportMapping,
    val poses: List<ModelResult>,
    val poseToken: PluginToken? = null,
    val executionProvider: String? = null,
) {
    val rawMapping: RawImageMapping get() = RawImageMapping(raw.width, raw.height, source)
}

internal data class PluginToken(val pluginId: String, val session: Long, val generation: Long)

internal data class PressToken(
    val session: Long,
    val sequence: Long,
    val pointerId: Long,
    val startedNanos: Long,
    val firstFrame: Long,
)

internal enum class CancelReason { OUT_OF_BOUNDS, MULTITOUCH, SYSTEM_CANCEL, VIEWPORT_CHANGED, BACKGROUND, LEAVE, STOP }
internal enum class CameraLifecycle { ENTER, FOREGROUND, BACKGROUND, LEAVE, STOP }
internal enum class AudioPermission { NOT_REQUESTED, GRANTED, DENIED, REVOKED, MICROPHONE_BUSY }
internal enum class PcmFormat(val bytesPerSample: Int) { PCM_16_LE(2), PCM_FLOAT_LE(4) }

/** Capture belongs to the downstream audio owner. Pass a read-only, independently owned buffer. */
internal class PcmBlock(
    samples: ByteBuffer,
    val sampleRate: Int,
    val channels: Int,
    val format: PcmFormat,
    val timestampNanos: Long,
) {
    private val data = samples.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    /** Each reader gets independent position/limit as well as read-only sample bytes. */
    val samples: ByteBuffer get() = data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(samples.isReadOnly)
        require(sampleRate > 0 && channels in 1..2)
        require(samples.remaining() <= 1024 * 1024)
        require(samples.remaining() % (channels * format.bytesPerSample) == 0)
    }
}

internal sealed interface PluginEvent {
    data object Started : PluginEvent
    data object ConfigurationChanged : PluginEvent
    data class Stopped(val reason: CameraLifecycle) : PluginEvent
    data class Lifecycle(val state: CameraLifecycle) : PluginEvent
    data class Frame(val frame: CameraFrame) : PluginEvent
    /** After synchronous pose/frame consumers return. Async work is explicitly separate. */
    data class FrameCompleted(
        val frame: CameraFrame,
        val timing: FrameProcessingTiming,
        val asyncResults: List<PluginTimingContribution>,
    ) : PluginEvent
    data class Pose(val frame: CameraFrame) : PluginEvent
    data class Press(val token: PressToken, val position: ImagePoint, val timestampNanos: Long) : PluginEvent
    data class Release(val token: PressToken, val position: ImagePoint, val timestampNanos: Long) : PluginEvent
    data class Cancel(val token: PressToken, val position: ImagePoint, val timestampNanos: Long, val reason: CancelReason) : PluginEvent
    data class AudioPermissionChanged(val permission: AudioPermission) : PluginEvent
    data class Audio(val block: PcmBlock) : PluginEvent
    data class AudioPlaybackChanged(val playing: Boolean) : PluginEvent
    data class Action(val id: String, val frame: CameraFrame?, val timestampNanos: Long) : PluginEvent
}

internal enum class MessageKind { TEXT, STATUS, ERROR }
internal data class PluginMessage(val text: String, val kind: MessageKind = MessageKind.TEXT)
internal data class OverlayLine(
    val start: ImagePoint,
    val end: ImagePoint,
    val color: Int,
    val label: String? = null,
)

internal data class PluginLayer(
    val source: ImageSource,
    val image: PluginImage? = null,
    val lines: List<OverlayLine> = emptyList(),
)

/** A full source-frame composite, not an ROI to paste onto the latest camera frame. */
internal data class PreviewReplacement(val source: ImageSource, val image: PluginImage, val press: PressToken)

internal data class PluginAction(val id: String, val label: String, val enabled: Boolean = true)

/** Fractions of the upright photograph, excluding letterbox; also used for the actual input crop. */
internal data class PhotoRoi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom)
    }
}

internal data class PluginGuide(val roi: PhotoRoi, val label: String, val focusRoi: PhotoRoi? = null)

/** Slow results live beside their captured input, never as an overlay on an unrelated live frame. */
internal data class PluginImageReport(
    /** null means an explicitly labelled file/generated image, never the current camera frame. */
    val source: ImageSource?,
    val input: PluginImage,
    val result: PluginImage? = null,
    val label: String = "来源画面",
)

internal data class FrameProcessingTiming(val dispatchMs: Double, val totalMs: Double)
internal data class PluginExecution(val elapsedMs: Double, val backend: String, val label: String = "本次推理")
internal data class PluginTimingContribution(
    val pluginId: String,
    val name: String,
    val execution: PluginExecution,
    val completedNanos: Long = 0L,
    val source: ImageSource? = null,
)

internal data class PluginOutput(
    val timestampNanos: Long,
    val messages: List<PluginMessage> = emptyList(),
    val layers: List<PluginLayer> = emptyList(),
    val replacement: PreviewReplacement? = null,
    val actions: List<PluginAction> = emptyList(),
    val imageReport: PluginImageReport? = null,
    val guide: PluginGuide? = null,
    /** This plugin's last completed asynchronous call; never sum this into frame wall time. */
    val execution: PluginExecution? = null,
    /** User/contract intent, including while permission is missing. null uses continuousAudio;
     * false releases capture and withdraws permission demand. Permission events still reach all enabled subscribers. */
    val captureAudio: Boolean? = null,
)

internal interface CameraPlugin : AutoCloseable {
    val descriptor: PluginDescriptor
    /** Control callbacks run on the camera UI owner; frame callbacks run on its analysis worker.
     * Keep control callbacks short. Use AsyncModelSlot for serialized native model ownership.
     * Use BoundedFrameWorker for async consumers that must not hold up the latest-frame stream.
     */
    fun onEvent(event: PluginEvent, context: PluginContext)
    override fun close() {}
}

internal class PluginContext internal constructor(
    val token: PluginToken,
    val settings: PluginSettings,
    private val host: PluginHost,
) {
    fun isCurrent(): Boolean = host.isCurrent(token)
    fun publish(output: PluginOutput): Boolean = host.publish(token, output)
    fun currentPress(): PressToken? = host.currentPress(token)
    fun currentFrame(): CameraFrame? = host.currentFrame(token)
}

internal data class PluginContribution(val pluginId: String, val name: String, val output: PluginOutput)
internal data class HostSnapshot(
    val session: Long? = null,
    val frame: CameraFrame? = null,
    val replacement: PluginContribution? = null,
    val layers: List<PluginContribution> = emptyList(),
    /** Interval metrics persist independently of frame-bound drawing layers. */
    val monitors: List<PluginContribution> = emptyList(),
    val reports: List<PluginContribution> = emptyList(),
    val press: PressToken? = null,
    val guides: List<PluginContribution> = emptyList(),
    val audioCaptureRequested: Boolean = false,
    val audioPermission: AudioPermission = AudioPermission.NOT_REQUESTED,
    val audioPermissionRequested: Boolean = false,
) {
    val displayedSource: ImageSource? get() = replacement?.output?.replacement?.source ?: frame?.source
    val displayedImage: PluginImage? get() = replacement?.output?.replacement?.image ?: frame?.image
}
