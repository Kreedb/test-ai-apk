package com.example.minimalapk.examples

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.min

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
