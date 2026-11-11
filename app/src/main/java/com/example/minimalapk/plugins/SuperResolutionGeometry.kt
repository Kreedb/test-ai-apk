package com.example.minimalapk.plugins

import com.example.minimalapk.examples.SR_INPUT_SIZE
import com.example.minimalapk.examples.SR_OUTPUT_SIZE
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Default ROI: the center half of the frame's smaller side, never the whole preview. */
internal const val SUPER_RESOLUTION_ROI_FRACTION = 0.5f

internal data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

internal data class SuperResolutionRoi(
    /** Square ROI in upright source pixels; also the destination on the full-frame composite. */
    val source: PixelRect,
    /** The same ROI in original camera pixels, used for the 50×50 model crop. */
    val raw: PixelRect,
    /** Fixed model input rectangle. */
    val input: PixelRect,
) {
    val composite: PixelRect get() = source
}

/**
 * Maps one square ROI from the original sensor frame to the upright source/composite frame.
 * The source rect is derived from the raw rect through [RawImageMapping], so 90/270 rotation and
 * any raw/source scale (including letterboxing) stay aligned. Nothing here claims whole-frame SR.
 */
internal fun superResolutionRoi(
    source: ImageSource,
    rawWidth: Int,
    rawHeight: Int,
    fraction: Float = SUPER_RESOLUTION_ROI_FRACTION,
    inputSize: Int = SR_INPUT_SIZE,
    outputSize: Int = SR_OUTPUT_SIZE,
): SuperResolutionRoi {
    require(fraction > 0f && fraction <= 1f) { "ROI fraction must be in (0, 1]" }
    require(inputSize > 0 && outputSize > 0)
    val mapping = RawImageMapping(rawWidth, rawHeight, source)
    val smaller = min(rawWidth, rawHeight)
    val rawSide = (smaller * fraction).roundToInt().coerceIn(1, smaller)
    val raw = PixelRect(
        left = (rawWidth - rawSide) / 2,
        top = (rawHeight - rawSide) / 2,
        width = rawSide,
        height = rawSide,
    )

    val center = mapping.toImage(ImagePoint(rawWidth / 2f, rawHeight / 2f))
    val edge = mapping.toImage(ImagePoint(rawWidth / 2f + rawSide / 2f, rawHeight / 2f))
    // Rotation puts the raw-x offset on either the source x or y axis; the length is the sum.
    val half = abs(edge.x - center.x) + abs(edge.y - center.y)
    val bounds = source.space.photoBounds
    val photoWidth = floor(bounds.right - bounds.left).toInt()
    val photoHeight = floor(bounds.bottom - bounds.top).toInt()
    val maxSide = min(min(photoWidth, photoHeight), min(source.space.width, source.space.height))
        .coerceAtLeast(1)
    val side = (half * 2f).roundToInt().coerceIn(1, maxSide)
    val minLeft = ceil(bounds.left).toInt().coerceIn(0, source.space.width - side)
    val minTop = ceil(bounds.top).toInt().coerceIn(0, source.space.height - side)
    val maxLeft = (floor(bounds.right).toInt() - side).coerceAtLeast(minLeft)
    val maxTop = (floor(bounds.bottom).toInt() - side).coerceAtLeast(minTop)
    val left = (center.x - side / 2f).roundToInt().coerceIn(minLeft, maxLeft)
    val top = (center.y - side / 2f).roundToInt().coerceIn(minTop, maxTop)
    return SuperResolutionRoi(
        source = PixelRect(left, top, side, side),
        raw = raw,
        input = PixelRect(0, 0, inputSize, inputSize),
    )
}
