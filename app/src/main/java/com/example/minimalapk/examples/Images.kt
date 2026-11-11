package com.example.minimalapk.examples

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.min

/** A whole-image fit, not a crop. Coordinates are normalized model coordinates ↔ source pixels. */
internal data class ImageFit(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0)
    }

    val scale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
    val left = (width - sourceWidth * scale) / 2f
    val top = (height - sourceHeight * scale) / 2f
    val photoWidth = sourceWidth * scale
    val photoHeight = sourceHeight * scale

    fun sourceX(modelX: Float): Float = (modelX * width - left) / scale
    fun sourceY(modelY: Float): Float = (modelY * height - top) / scale
    fun contains(modelX: Float, modelY: Float): Boolean =
        modelX.isFinite() && modelY.isFinite() && sourceX(modelX) >= 0f &&
            sourceX(modelX) < sourceWidth && sourceY(modelY) >= 0f && sourceY(modelY) < sourceHeight
}

internal data class FittedImage(val bitmap: Bitmap, val fit: ImageFit)

/** Rotates the borrowed sensor image directly into a bounded input, preserving all four edges. */
internal fun fittedImage(source: Bitmap, width: Int, height: Int, rotationDegrees: Int = 0): FittedImage {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    require(rotation in listOf(0, 90, 180, 270))
    val swapped = rotation == 90 || rotation == 270
    val fit = ImageFit(
        if (swapped) source.height else source.width,
        if (swapped) source.width else source.height,
        width, height,
    )
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(0xff000000.toInt())
        translate(width / 2f, height / 2f)
        rotate(rotation.toFloat())
        scale(fit.scale, fit.scale)
        translate(-source.width / 2f, -source.height / 2f)
        drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return FittedImage(bitmap, fit)
}

/** Decodes a SAF image with a memory cap; software allocator keeps pixels readable on API 28+. */
internal fun decodeImage(context: Context, uri: Uri, maxSize: Int = 2048): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSize) {
                decoder.setTargetSampleSize(sampleSizeFor(longest, maxSize))
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSizeFor(max(bounds.outWidth, bounds.outHeight), maxSize)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

/** Power-of-two subsampling so the decoded image stays near [maxSize] on its long side. */
internal fun sampleSizeFor(longest: Int, maxSize: Int): Int {
    if (maxSize <= 0) return 1
    var sample = 1
    while (longest / sample > maxSize) sample *= 2
    return sample
}

/** Center-crops to a square and resizes to the model's fixed input. */
internal fun squareBitmap(bitmap: Bitmap, size: Int): Bitmap {
    val edge = min(bitmap.width, bitmap.height)
    val cropped = Bitmap.createBitmap(
        bitmap,
        (bitmap.width - edge) / 2,
        (bitmap.height - edge) / 2,
        edge,
        edge,
    )
    // Do not recycle intermediates: createBitmap/createScaledBitmap may return the input itself,
    // and the caller's bitmap can still be on screen.
    return cropped.scale(size, size)
}
