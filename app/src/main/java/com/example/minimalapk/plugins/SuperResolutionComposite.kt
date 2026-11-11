package com.example.minimalapk.plugins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.minimalapk.examples.SuperResolutionTiles
import com.example.minimalapk.examples.fittedImage

/** Compose every tile into this press's source frame, never onto the moving live preview.
 * A cancelled/failed composition owns and recycles its partial image; borrowed inputs stay intact. */
internal fun superResolutionComposite(
    frame: CameraFrame,
    plan: SuperResolutionPlan,
    runner: SuperResolutionTiles,
    keepGoing: () -> Boolean,
    onTile: (Int) -> Unit = {},
): Bitmap? {
    if (!keepGoing()) return null
    val fitted = fittedImage(frame.raw.bitmap, plan.width, plan.height, frame.source.rotationDegrees)
    val pixels = IntArray(plan.width * plan.height)
    try {
        fitted.bitmap.getPixels(pixels, 0, plan.width, 0, 0, plan.width, plan.height)
    } finally { fitted.bitmap.recycle() }
    val (width, height) = superResolutionCompositeSize(frame.source.space, plan)
    val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var completed = false
    try {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val canvas = Canvas(base)
        canvas.drawBitmap(frame.image.bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        val bounds = frame.source.space.photoBounds
        val sx = width.toFloat() / frame.source.space.width
        val sy = height.toFloat() / frame.source.space.height
        val photoWidth = (bounds.right - bounds.left) * sx
        val photoHeight = (bounds.bottom - bounds.top) * sy
        canvas.clipRect(bounds.left * sx, bounds.top * sy, bounds.right * sx, bounds.bottom * sy)
        for ((index, tile) in plan.tiles.withIndex()) {
            if (!keepGoing()) return null
            val upscaled = runner.runTile(pixels, plan.width, plan.height, tile.sampleLeft, tile.sampleTop)
            try {
                if (!keepGoing()) return null
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
            onTile(index + 1)
        }
        if (!keepGoing()) return null
        completed = true
        return base
    } finally {
        if (!completed) base.recycle()
    }
}
