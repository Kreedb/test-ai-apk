package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.pose.ModelResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class PluginCapability {
    CAMERA_FRAMES, POSE_RESULTS, POINTER, LIFECYCLE, AUDIO_PERMISSION, AUDIO_PCM,
    TEXT, LAYER, REPLACEMENT, MONITOR,
}

internal data class PluginDescriptor(
    val id: String,
    val name: String,
    val capabilities: Set<PluginCapability>,
    /** Higher wins; ties use the stable ID in ascending order, never completion order. */
    val replacementPriority: Int = 0,
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
    /** Original camera resolution/orientation, retained for e.g. source-frame super resolution. */
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
    data class Pose(val frame: CameraFrame) : PluginEvent
    data class Press(val token: PressToken, val position: ImagePoint, val timestampNanos: Long) : PluginEvent
    data class Release(val token: PressToken, val position: ImagePoint, val timestampNanos: Long) : PluginEvent
    data class Cancel(val token: PressToken, val position: ImagePoint, val timestampNanos: Long, val reason: CancelReason) : PluginEvent
    data class AudioPermissionChanged(val permission: AudioPermission) : PluginEvent
    data class Audio(val block: PcmBlock) : PluginEvent
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

internal data class PluginOutput(
    val timestampNanos: Long,
    val messages: List<PluginMessage> = emptyList(),
    val layers: List<PluginLayer> = emptyList(),
    val replacement: PreviewReplacement? = null,
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
}

internal data class PluginContribution(val pluginId: String, val name: String, val output: PluginOutput)
internal data class HostSnapshot(
    val session: Long? = null,
    val frame: CameraFrame? = null,
    val replacement: PluginContribution? = null,
    val layers: List<PluginContribution> = emptyList(),
    val monitors: List<PluginContribution> = emptyList(),
    val reports: List<PluginContribution> = emptyList(),
    val press: PressToken? = null,
) {
    val displayedSource: ImageSource? get() = replacement?.output?.replacement?.source ?: frame?.source
    val displayedImage: PluginImage? get() = replacement?.output?.replacement?.image ?: frame?.image
}
