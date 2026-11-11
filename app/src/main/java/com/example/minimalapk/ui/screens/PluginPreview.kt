package com.example.minimalapk.ui.screens

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import com.example.minimalapk.plugins.CancelReason
import com.example.minimalapk.plugins.HostSnapshot
import com.example.minimalapk.plugins.ImagePoint
import com.example.minimalapk.plugins.ImageSource
import com.example.minimalapk.plugins.PluginCapability
import com.example.minimalapk.plugins.PluginHost
import com.example.minimalapk.plugins.PluginLayer
import com.example.minimalapk.plugins.PluginGuide
import com.example.minimalapk.plugins.RawImageMapping
import com.example.minimalapk.plugins.ViewportMapping
import com.example.minimalapk.pose.ModelResult
import com.example.minimalapk.pose.PoseNet

/** Raw camera pixels → the same centered Crop space used by poses, layers and pointer events.
 * Compose the existing mappings instead of rounding a second letterbox or stretching the photo
 * to a square. The matrix applies to the full-resolution raw bitmap, never the model bitmap.
 */
internal fun rawPreviewMatrix(
    rawWidth: Int,
    rawHeight: Int,
    source: ImageSource,
    viewportWidth: Int,
    viewportHeight: Int,
): FloatArray {
    val raw = RawImageMapping(rawWidth, rawHeight, source)
    val viewport = ViewportMapping(source.space, viewportWidth, viewportHeight)
    fun map(x: Float, y: Float) = viewport.toPreview(raw.toImage(ImagePoint(x, y)))
    val origin = map(0f, 0f)
    val right = map(rawWidth.toFloat(), 0f)
    val bottom = map(0f, rawHeight.toFloat())
    return floatArrayOf(
        (right.x - origin.x) / rawWidth, (bottom.x - origin.x) / rawHeight, origin.x,
        (right.y - origin.y) / rawWidth, (bottom.y - origin.y) / rawHeight, origin.y,
        0f, 0f, 1f,
    )
}

/** Display raw camera detail independently of the models' input resolution. A replacement is
 * already an upright, full source-space composite and keeps the existing Crop transform.
 */
@Composable
internal fun CameraFramePreview(snapshot: HostSnapshot, modifier: Modifier = Modifier) {
    val frame = snapshot.frame ?: return
    val replacement = snapshot.replacement?.output?.replacement
    if (replacement != null) {
        Image(replacement.image.bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val matrix = remember { Matrix() }
        Canvas(modifier) {
            val padding = PoseNet.LETTERBOX_COLOR
            drawRect(Color(padding, padding, padding))
            matrix.setValues(rawPreviewMatrix(
                frame.raw.width, frame.raw.height, frame.source, size.width.toInt(), size.height.toInt(),
            ))
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            try {
                canvas.clipRect(0f, 0f, size.width, size.height)
                canvas.drawBitmap(frame.raw.bitmap, matrix, paint)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }
}

/** Down is immediate; a second pointer invalidates the entire gesture until all fingers are up. */
internal fun Modifier.pluginPressInput(host: PluginHost): Modifier = pointerInput(host) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown()
            val token = host.press(down.id.value, ImagePoint(down.position.x, down.position.y), System.nanoTime())
            if (currentEvent.changes.count { it.pressed } > 1) host.cancel(CancelReason.MULTITOUCH)
            if (token != null) down.consume()
            try {
                do {
                    val event = awaitPointerEvent()
                    val tracked = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1) host.cancel(CancelReason.MULTITOUCH)
                    if (tracked != null) {
                        if (tracked.isConsumed) host.cancel(CancelReason.SYSTEM_CANCEL)
                        val point = ImagePoint(tracked.position.x, tracked.position.y)
                        if (tracked.pressed) host.move(down.id.value, point, System.nanoTime())
                        else host.release(down.id.value, point, System.nanoTime())
                        if (token != null) tracked.consume()
                    } else host.cancel(CancelReason.SYSTEM_CANCEL)
                } while (event.changes.any { it.pressed })
            } finally {
                host.cancel(CancelReason.SYSTEM_CANCEL)
            }
        }
    } finally {
        host.cancel(CancelReason.SYSTEM_CANCEL)
    }
}.semantics {
    if (host.hasSubscribers(PluginCapability.POINTER)) {
        contentDescription = "相机预览"
        stateDescription = if (host.snapshot.value.press != null) "按压中" else "实时画面"
        customActions = listOf(
            CustomAccessibilityAction("开始按压预览") {
                val viewport = host.snapshot.value.frame?.viewport
                viewport != null && host.press(-1L, ImagePoint(viewport.width / 2f, viewport.height / 2f), System.nanoTime()) != null
            },
            CustomAccessibilityAction("结束按压，恢复实时画面") {
                host.cancel(CancelReason.SYSTEM_CANCEL)
                true
            },
        )
    }
}

@Composable
internal fun BasePoseOverlay(primary: ModelResult?, source: ImageSource, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val detection = primary?.detection ?: return@Canvas
        val mapping = ViewportMapping(source.space, size.width.toInt(), size.height.toInt())
        fun point(index: Int): Offset? {
            val keypoint = detection.keypoints.getOrNull(index) ?: return null
            if (keypoint.confidence < primary.spec.keypointThreshold) return null
            return mapping.toPreview(ImagePoint(keypoint.x, keypoint.y)).let { Offset(it.x, it.y) }
        }
        val skeletonColor = Color(0xFF00E5FF)
        PoseNet.skeletonEdges.forEach { (first, second) ->
            val start = point(first) ?: return@forEach
            val end = point(second) ?: return@forEach
            drawLine(skeletonColor, start, end, strokeWidth = 3f)
        }
        detection.keypoints.indices.forEach { index ->
            point(index)?.let { drawCircle(Color(0xFFFFC107), radius = 5f, center = it) }
        }
        val origin = mapping.toPreview(ImagePoint(detection.left, detection.top))
        drawRect(
            color = Color(0x9900E5FF), topLeft = Offset(origin.x, origin.y),
            size = Size(detection.width * mapping.scale, detection.height * mapping.scale), style = Stroke(3f),
        )
    }
}

@Composable
internal fun PluginLineOverlay(layer: PluginLayer, modifier: Modifier = Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    Canvas(modifier) {
        val mapping = ViewportMapping(layer.source.space, size.width.toInt(), size.height.toInt())
        layer.lines.forEach { line ->
            val a = mapping.toPreview(line.start)
            val b = mapping.toPreview(line.end)
            val start = Offset(a.x, a.y)
            val end = Offset(b.x, b.y)
            val color = Color(line.color)
            drawLine(color, start, end, 4f)
            drawCircle(color, 7f, start)
            drawCircle(color, 7f, end)
            line.label?.let { label ->
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 12.sp.toPx()
                paint.setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                drawContext.canvas.nativeCanvas.drawText(label, (a.x + b.x) / 2 + 12f, (a.y + b.y) / 2, paint)
            }
        }
    }
}

/** A guide is a requested crop, not a model detection; it uses the exact normalized input ROI. */
@Composable
internal fun PluginPhotoGuide(guide: PluginGuide, source: ImageSource, modifier: Modifier = Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.YELLOW } }
    Canvas(modifier) {
        val bounds = source.space.photoBounds
        val mapping = ViewportMapping(source.space, size.width.toInt(), size.height.toInt())
        val roi = guide.roi
        val a = mapping.toPreview(ImagePoint(bounds.left + (bounds.right - bounds.left) * roi.left,
            bounds.top + (bounds.bottom - bounds.top) * roi.top))
        val b = mapping.toPreview(ImagePoint(bounds.left + (bounds.right - bounds.left) * roi.right,
            bounds.top + (bounds.bottom - bounds.top) * roi.bottom))
        drawRect(Color.Yellow, Offset(a.x, a.y), Size(b.x - a.x, b.y - a.y), style = Stroke(3f))
        guide.focusRoi?.let { focus ->
            val c = mapping.toPreview(ImagePoint(bounds.left + (bounds.right - bounds.left) * focus.left,
                bounds.top + (bounds.bottom - bounds.top) * focus.top))
            val d = mapping.toPreview(ImagePoint(bounds.left + (bounds.right - bounds.left) * focus.right,
                bounds.top + (bounds.bottom - bounds.top) * focus.bottom))
            drawRect(Color.Cyan, Offset(c.x, c.y), Size(d.x - c.x, d.y - c.y), style = Stroke(3f))
        }
        paint.textSize = 12.sp.toPx()
        paint.setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        drawContext.canvas.nativeCanvas.drawText(guide.label, a.x, (a.y - 8f).coerceAtLeast(paint.textSize), paint)
    }
}
