/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.minimalapk.litert.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.minimalapk.litert.ImageTaskOutput
import com.example.minimalapk.litert.ImageTaskRequest
import com.example.minimalapk.litert.SingleImageEngine
import com.example.minimalapk.litert.compileImageBackend
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** A secondary bitmap is an explicitly selected face crop; otherwise use the exact drawn ROI. */
private class FaceInput(request: ImageTaskRequest) : AutoCloseable {
    val selected = request.secondaryBitmap != null
    val source = request.secondaryBitmap ?: request.bitmap
    val region = if (selected) PixelRegion(0, 0, source.width, source.height)
        else VisionGeometry.centeredFace(source.width, source.height)
    val crop: Bitmap = Bitmap.createBitmap(source, region.left, region.top, region.width, region.height)
    val description = if (selected) "user-selected face crop" else "centered 60% face ROI; no face detector"
    override fun close() { if (crop !== source) crop.recycle() }
}

internal class LivenessImageEngine(directory: File, backend: String) : SingleImageEngine {
    private val loaded = compileImageBackend(backend, "VisionLiveness") {
        LivenessDetector(File(directory, "silentface.tflite"), it)
    }

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        val start = System.nanoTime()
        FaceInput(request).use { input ->
            val result = loaded.runner.detect(input.crop)
            val output = input.source.visionCopy()
            val canvas = Canvas(output)
            canvas.drawRegion(input.region, visionStroke(output.width, Color.YELLOW))
            val guide = VisionGeometry.livenessGuide(input.region)
            canvas.drawRegion(guide, visionStroke(output.width, Color.CYAN))
            return ImageTaskOutput(
                bitmap = output,
                text = String.format(Locale.US, "Live sample score: %.1f%% · %s\n%s",
                    result.liveScore * 100, if (result.isLive) "live-like" else "print/replay-like",
                    if (input.selected) "Selected crop, including face context." else "Center the face in the cyan guide; yellow is the model crop (2.7× context)."),
                inferenceMs = (System.nanoTime() - start) / 1e6,
                backend = loaded.backend,
                fallbackReason = loaded.fallbackReason,
                metrics = mapOf("isLive" to result.isLive, "liveScore" to result.liveScore,
                    "classProbabilities" to result.probabilities, "faceRoi" to input.region.asList(),
                    "faceGuide" to guide.asList(), "crop" to input.description),
                details = "MiniFASNetV2 is a single 2.7×-crop print/replay comparison model. Scores depend on " +
                    "lighting, capture device and training conditions; they do not establish identity or provide authentication.",
            )
        }
    }

    override fun close() = loaded.runner.close()
}

internal class HeadPoseImageEngine(directory: File, backend: String) : SingleImageEngine {
    private val loaded = compileImageBackend(backend, "VisionHeadPose") {
        HeadPoseEstimator(File(directory, "6drepnet.tflite"), it)
    }

    override fun run(request: ImageTaskRequest): ImageTaskOutput {
        val start = System.nanoTime()
        FaceInput(request).use { input ->
            val pose = loaded.runner.estimate(input.crop)
            val output = input.source.visionCopy()
            val canvas = Canvas(output)
            canvas.drawRegion(input.region, visionStroke(output.width))
            drawHeadPose(canvas, input.region, pose, output.width)
            return ImageTaskOutput(
                bitmap = output,
                text = String.format(Locale.US, "Yaw %.1f° · Pitch %.1f° · Roll %.1f°\n%s", pose.yaw, pose.pitch, pose.roll,
                    if (input.selected) "Estimated from the selected face crop." else "Fill the yellow guide with one face; only that ROI is processed."),
                inferenceMs = (System.nanoTime() - start) / 1e6,
                backend = loaded.backend,
                fallbackReason = loaded.fallbackReason,
                metrics = mapOf("yawDegrees" to pose.yaw, "pitchDegrees" to pose.pitch, "rollDegrees" to pose.roll,
                    "faceRoi" to input.region.asList(), "crop" to input.description),
                details = "6DRepNet face-crop estimate. Axes: red X, green Y, blue Z; non-face crops and strong occlusion are outside this model's input contract.",
            )
        }
    }

    override fun close() = loaded.runner.close()
}

/** Render-only axis projection from Model Zoo RealtimeImageTasks; centered on the actual crop. */
private fun drawHeadPose(canvas: Canvas, region: PixelRegion, pose: HeadPose, width: Int) {
    val cx = (region.left + region.right) / 2f
    val cy = (region.top + region.bottom) / 2f
    val length = minOf(region.width, region.height) * 0.4f
    val pitch = Math.toRadians(pose.pitch.toDouble())
    val yaw = Math.toRadians(-pose.yaw.toDouble())
    val roll = Math.toRadians(pose.roll.toDouble())
    val paint = visionStroke(width)
    paint.color = Color.RED
    canvas.drawLine(cx, cy, cx + length * (cos(yaw) * cos(roll)).toFloat(),
        cy + length * (cos(pitch) * sin(roll) + cos(roll) * sin(pitch) * sin(yaw)).toFloat(), paint)
    paint.color = Color.GREEN
    canvas.drawLine(cx, cy, cx + length * (-cos(yaw) * sin(roll)).toFloat(),
        cy + length * (cos(pitch) * cos(roll) - sin(pitch) * sin(yaw) * sin(roll)).toFloat(), paint)
    paint.color = Color.BLUE
    canvas.drawLine(cx, cy, cx + length * sin(yaw).toFloat(), cy + length * (-cos(yaw) * sin(pitch)).toFloat(), paint)
}
