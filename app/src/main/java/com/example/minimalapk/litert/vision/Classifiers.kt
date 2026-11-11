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
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.litert.CompiledModelRunner
import com.google.ai.edge.litert.Accelerator
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ClassPrediction(val index: Int, val label: String, val score: Float)

/** Model Zoo PlantNet wrapper: ImageFolder's lexicographic species-ID order is significant. */
internal class PlantClassifier(model: File, labelsFile: File, accelerator: Accelerator) : AutoCloseable {
    private val labels = JSONObject(labelsFile.readText()).let { json ->
        speciesLabels(json.keys().asSequence().associateWith { json.getString(it) })
    }.also { require(it.size == 1081) { "PlantNet requires 1081 species names" } }
    private val runner = CompiledModelRunner.fromFile(model.absolutePath, accelerator)

    fun classify(bitmap: Bitmap): List<ClassPrediction> {
        val side = minOf(bitmap.width, bitmap.height)
        // Keep the upstream half-pixel center when an odd difference cannot be an integer crop.
        val left = (bitmap.width - side) / 2f
        val top = (bitmap.height - side) / 2f
        val input = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        try {
            val scale = 224f / side
            Canvas(input).drawBitmap(bitmap, null,
                RectF(-left * scale, -top * scale, (bitmap.width - left) * scale, (bitmap.height - top) * scale),
                Paint(Paint.FILTER_BITMAP_FLAG))
            runner.writeInput(0, VisionMath.imageNetChw(input.visionPixels()))
        } finally { input.recycle() }
        runner.run()
        val logits = runner.readOutput(0)
        check(logits.size == labels.size)
        return VisionMath.topK(logits, 5).map { ClassPrediction(it.index, labels[it.index], it.probability) }
    }

    override fun close() = runner.close()

    companion object {
        fun speciesLabels(species: Map<String, String>): List<String> = species.keys.sorted().map { species.getValue(it) }
    }
}

/** Model Zoo Vision-RWKV-S: short edge 256 -> center 224, plus |token - token| runtime input. */
internal class VrwkvClassifier(model: File, labelsFile: File, accelerator: Accelerator) : AutoCloseable {
    private val labels = labelsFile.readLines().also { require(it.size == 1000) { "Vision-RWKV requires 1000 ImageNet labels" } }
    private val runner = CompiledModelRunner.fromFile(model.absolutePath, accelerator)
    private val distances = tokenDistances()

    fun classify(bitmap: Bitmap): List<ClassPrediction> {
        val scale = 256f / minOf(bitmap.width, bitmap.height)
        val width = (bitmap.width * scale).roundToInt()
        val height = (bitmap.height * scale).roundToInt()
        // Render only the center window instead of allocating an unbounded panoramic resize.
        val left = (width - 224) / 2
        val top = (height - 224) / 2
        val cropped = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        try {
            Canvas(cropped).drawBitmap(bitmap, null,
                RectF(-left.toFloat(), -top.toFloat(), (width - left).toFloat(), (height - top).toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG))
            runner.writeInput(0, VisionMath.imageNetChw(cropped.visionPixels()))
        } finally { cropped.recycle() }
        runner.writeInput(1, distances)
        runner.run()
        val logits = runner.readOutput(0)
        check(logits.size == labels.size)
        return VisionMath.topK(logits, 5).map { ClassPrediction(it.index, labels[it.index], it.probability) }
    }

    override fun close() = runner.close()

    companion object {
        const val TOKENS = 196
        fun tokenDistances(): FloatArray = FloatArray(TOKENS * TOKENS) { abs(it / TOKENS - it % TOKENS).toFloat() }
    }
}

/** Only the catalog's aesthetic AVA graph is loaded; its output is not a sharpness measurement. */
internal class NimaScorer(file: File, accelerator: Accelerator) : AutoCloseable {
    private val runner = CompiledModelRunner.fromFile(file.absolutePath, accelerator)

    fun score(bitmap: Bitmap): FloatArray {
        runner.writeInput(0, VisionMath.mobileNetNhwc(resizedPixels(bitmap, 224)))
        runner.run()
        return runner.readOutput(0).also { meanScore(it) }
    }

    override fun close() = runner.close()

    companion object {
        fun meanScore(distribution: FloatArray): Float {
            require(distribution.size == 10 && distribution.all { it.isFinite() && it >= 0f })
            require(abs(distribution.sum() - 1f) < 0.05f) { "NIMA output is not a probability distribution" }
            return distribution.indices.sumOf { (it + 1) * distribution[it].toDouble() }.toFloat()
        }
    }
}
