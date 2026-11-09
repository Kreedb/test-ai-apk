package com.example.minimalapk.pose

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the rotated camera frame lands inside the square model input.
 *
 * [rotationDegrees] is CameraX's clockwise rotation that makes the frame upright, so 90 and 270
 * swap the source dimensions before letterboxing.
 */
internal data class PoseInputLayout(
    val rotationDegrees: Int,
    val scale: Float,
    val targetWidth: Int,
    val targetHeight: Int,
    val left: Int,
    val top: Int,
) {
    val centerX: Float get() = left + targetWidth / 2f
    val centerY: Float get() = top + targetHeight / 2f
    val photoBounds: PhotoBounds
        get() = PhotoBounds(
            left = left.toFloat(),
            top = top.toFloat(),
            right = (left + targetWidth).toFloat(),
            bottom = (top + targetHeight).toFloat(),
        )
}

/**
 * Computes the rotated, aspect-preserving letterbox placement without touching a
 * [android.graphics.Bitmap], so the 0/90/180/270 dimension swap stays unit-testable.
 */
internal fun poseInputLayout(width: Int, height: Int, rotationDegrees: Int): PoseInputLayout {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    val swapDimensions = rotation == 90 || rotation == 270
    val rotatedWidth = if (swapDimensions) height else width
    val rotatedHeight = if (swapDimensions) width else height
    val scale = min(
        PoseNet.INPUT_SIZE.toFloat() / rotatedWidth,
        PoseNet.INPUT_SIZE.toFloat() / rotatedHeight,
    )
    val targetWidth = (rotatedWidth * scale).roundToInt()
    val targetHeight = (rotatedHeight * scale).roundToInt()
    return PoseInputLayout(
        rotationDegrees = rotation,
        scale = scale,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        left = (PoseNet.INPUT_SIZE - targetWidth) / 2,
        top = (PoseNet.INPUT_SIZE - targetHeight) / 2,
    )
}
