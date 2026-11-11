package com.example.minimalapk.plugins

import com.example.minimalapk.pose.PhotoBounds
import kotlin.math.max

internal data class ImagePoint(val x: Float, val y: Float)

/** Coordinates are upright preview pixels, including the model input's letterbox padding. */
internal data class ImageSpace(val width: Int, val height: Int, val photoBounds: PhotoBounds) {
    init {
        require(width > 0 && height > 0)
        require(photoBounds.left >= 0 && photoBounds.top >= 0)
        require(photoBounds.right <= width && photoBounds.bottom <= height)
        require(photoBounds.left < photoBounds.right && photoBounds.top < photoBounds.bottom)
    }
}

/** Equality is deliberately strict: another frame, rotation, input size or ROI is not compatible. */
internal data class ImageSource(
    val session: Long,
    val frameNumber: Long,
    val space: ImageSpace,
    val rotationDegrees: Int,
)

/** Original unrotated camera pixels ↔ the upright, rounded letterbox used by PoseAnalyzer. */
internal data class RawImageMapping(val rawWidth: Int, val rawHeight: Int, val source: ImageSource) {
    private val rotation = ((source.rotationDegrees % 360) + 360) % 360
    private val rotatedWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
    private val rotatedHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
    private val scale = minOf(source.space.width.toFloat() / rotatedWidth, source.space.height.toFloat() / rotatedHeight)
    private val centerX = (source.space.photoBounds.left + source.space.photoBounds.right) / 2f
    private val centerY = (source.space.photoBounds.top + source.space.photoBounds.bottom) / 2f

    init {
        require(rawWidth > 0 && rawHeight > 0 && rotation in listOf(0, 90, 180, 270))
    }

    fun toImage(point: ImagePoint): ImagePoint {
        val x = point.x - rawWidth / 2f
        val y = point.y - rawHeight / 2f
        val rotated = when (rotation) {
            90 -> ImagePoint(-y, x)
            180 -> ImagePoint(-x, -y)
            270 -> ImagePoint(y, -x)
            else -> ImagePoint(x, y)
        }
        return ImagePoint(centerX + rotated.x * scale, centerY + rotated.y * scale)
    }

    fun toRaw(point: ImagePoint): ImagePoint {
        val x = (point.x - centerX) / scale
        val y = (point.y - centerY) / scale
        val unrotated = when (rotation) {
            90 -> ImagePoint(y, -x)
            180 -> ImagePoint(-x, -y)
            270 -> ImagePoint(-y, x)
            else -> ImagePoint(x, y)
        }
        return ImagePoint(rawWidth / 2f + unrotated.x, rawHeight / 2f + unrotated.y)
    }
}

/** The exact centered ContentScale.Crop transform used by the Compose preview and every layer. */
internal data class ViewportMapping(val space: ImageSpace, val width: Int, val height: Int) {
    val scale: Float = max(width.toFloat() / space.width, height.toFloat() / space.height)
    val left: Float = (width - space.width * scale) / 2f
    val top: Float = (height - space.height * scale) / 2f

    fun toPreview(point: ImagePoint) = ImagePoint(left + point.x * scale, top + point.y * scale)

    fun toImage(point: ImagePoint): ImagePoint? = if (scale > 0) {
        ImagePoint((point.x - left) / scale, (point.y - top) / scale)
    } else null

    fun containsPhoto(point: ImagePoint): Boolean {
        if (point.x < 0 || point.y < 0 || point.x >= width || point.y >= height) return false
        val image = toImage(point) ?: return false
        val bounds = space.photoBounds
        return image.x >= bounds.left && image.x < bounds.right &&
            image.y >= bounds.top && image.y < bounds.bottom
    }
}
