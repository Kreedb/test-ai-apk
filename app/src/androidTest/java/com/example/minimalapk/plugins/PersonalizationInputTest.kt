package com.example.minimalapk.plugins

import android.graphics.Bitmap
import com.example.minimalapk.pose.PhotoBounds
import org.junit.Assert.*
import org.junit.Test

class PersonalizationInputTest {
    @Test
    fun cameraLearningInputUsesUprightRawPhotoIndependentOfOtherModelsLowResolutionInput() {
        val raw = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff336699.toInt())
            setPixel(100, 60, 0xffffcc22.toInt())
        }
        val analysis = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff000000.toInt()) }
        val space = ImageSpace(16, 16, PhotoBounds(3f, 0f, 13f, 16f))
        val frame = CameraFrame(ImageSource(1, 1, space, 90), 1,
            BitmapImage(raw), BitmapImage(analysis), ViewportMapping(space, 320, 640), emptyList())
        try {
            val input = personalizationCameraImage(frame)
            try {
                assertEquals(200, input.width)
                assertEquals(320, input.height)
                assertEquals(0xffffcc22.toInt(), input.getPixel(139, 100))
                assertEquals(0xff336699.toInt(), input.getPixel(30, 30))
                assertFalse(raw.isRecycled)
                assertFalse(analysis.isRecycled)
                assertEquals(0xff000000.toInt(), analysis.getPixel(8, 8))
            } finally { input.recycle() }
        } finally { raw.recycle(); analysis.recycle() }
    }

    @Test
    fun largeCameraPhotoIsBoundedBeforeFeatureExtraction() {
        val raw = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff5588bb.toInt()) }
        val space = ImageSpace(16, 16, PhotoBounds(0f, 2f, 16f, 14f))
        val frame = CameraFrame(ImageSource(1, 1, space, 0), 1,
            BitmapImage(raw), BitmapImage(raw), ViewportMapping(space, 16, 16), emptyList())
        try {
            val input = personalizationCameraImage(frame)
            try {
                assertEquals(512, input.width)
                assertEquals(384, input.height)
                assertEquals(0xff5588bb.toInt(), input.getPixel(0, 0))
                assertEquals(0xff5588bb.toInt(), input.getPixel(511, 383))
                assertFalse(raw.isRecycled)
            } finally { input.recycle() }
        } finally { raw.recycle() }
    }
}
