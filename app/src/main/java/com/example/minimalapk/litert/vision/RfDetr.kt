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
import com.example.minimalapk.litert.CompiledModelRunner
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import kotlin.math.exp

/**
 * Adapted from Model Zoo models/rfdetr/RfDetr.kt. The two real graphs surround host top-k/gather:
 * A: NCHW 384² -> 576×91 classes, 576×4 reference coordinates, 576×256 memory;
 * B: memory + top-300 reference coordinates -> 300×4 cxcywh boxes + 300×91 logits.
 */
internal class RfDetr(private val directory: File, private val accelerator: Accelerator) : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    private fun load(name: String): CompiledModelRunner = try {
        CompiledModelRunner.fromFile(File(directory, name).absolutePath, accelerator).also { resources.add(it) }
    } catch (failure: Throwable) {
        close()
        throw failure
    }

    private val a = load(MODEL_A)
    private val b = load(MODEL_B)
    private fun slot(buffers: List<TensorBuffer>, floats: Int): Int = try {
        buffers.indexOfFirst { it.readFloat().size == floats }.also {
            check(it >= 0) { "RF-DETR tensor with $floats floats not found" }
        }
    } catch (failure: Throwable) {
        close()
        throw failure
    }

    private val aClass = slot(a.outputBuffers, NPROP * NCLS)
    private val aCoord = slot(a.outputBuffers, NPROP * 4)
    private val aMemory = slot(a.outputBuffers, NPROP * HID)
    private val bMemory = slot(b.inputBuffers, NPROP * HID)
    private val bReference = slot(b.inputBuffers, NQ * 4)
    private val bBoxes = slot(b.outputBuffers, NQ * 4)
    private val bLogits = slot(b.outputBuffers, NQ * NCLS)

    fun detect(bitmap: Bitmap): List<Detection> {
        // The upstream adapter stretches to 384² (no letterbox). Decode back to source edges.
        a.writeInput(0, VisionMath.imageNetChw(resizedPixels(bitmap, SIZE)))
        a.run()
        val classes = a.readOutput(aClass)
        val coordinates = a.readOutput(aCoord)
        val memory = a.readOutput(aMemory)
        b.writeInput(bMemory, memory)
        b.writeInput(bReference, selectReferencePoints(classes, coordinates))
        b.run()
        return decode(b.readOutput(bBoxes), b.readOutput(bLogits))
    }

    /** Coordinates normalized to the stretched input, equivalently normalized source coordinates. */
    data class Detection(val cls: Int, val score: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)

    override fun close() {
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    companion object {
        const val MODEL_A = "rfdetr_graphA_fp16.tflite"
        const val MODEL_B = "rfdetr_graphB_fp16.tflite"
        const val SIZE = 384
        const val NPROP = 576
        const val NQ = 300
        const val NCLS = 91
        const val HID = 256
        const val SCORE_THRESH = 0.45f
        const val IOU_THRESH = 0.6f

        fun selectReferencePoints(classes: FloatArray, coordinates: FloatArray): FloatArray {
            require(classes.size == NPROP * NCLS && coordinates.size == NPROP * 4)
            require(classes.all { it.isFinite() } && coordinates.all { it.isFinite() })
            val scores = FloatArray(NPROP) { p ->
                var best = -Float.MAX_VALUE
                for (c in 0 until NCLS) best = maxOf(best, classes[p * NCLS + c])
                best
            }
            val order = scores.indices.sortedByDescending { scores[it] }
            return FloatArray(NQ * 4) { coordinates[order[it / 4] * 4 + it % 4] }
        }

        fun decode(boxes: FloatArray, logits: FloatArray): List<Detection> {
            require(boxes.size == NQ * 4 && logits.size == NQ * NCLS)
            require(boxes.all { it.isFinite() } && logits.all { it.isFinite() }) { "Non-finite RF-DETR output" }
            val candidates = ArrayList<Detection>()
            for (q in 0 until NQ) {
                var bestClass = 0
                for (c in 1 until NCLS) {
                    if (logits[q * NCLS + c] > logits[q * NCLS + bestClass]) bestClass = c
                }
                val score = (1.0 / (1.0 + exp(-logits[q * NCLS + bestClass].toDouble()))).toFloat()
                // COCO uses sparse category IDs; unused IDs are not valid object classes.
                if (score < SCORE_THRESH || COCO_LABELS[bestClass].isEmpty()) continue
                val i = q * 4
                if (boxes[i + 2] <= 0f || boxes[i + 3] <= 0f) continue
                candidates.add(Detection(bestClass, score, boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3]))
            }
            val kept = ArrayList<Detection>()
            for (candidate in candidates.sortedByDescending { it.score }) {
                if (kept.none { it.cls == candidate.cls && iou(it, candidate) > IOU_THRESH }) kept.add(candidate)
            }
            return kept
        }

        private fun iou(a: Detection, b: Detection): Float {
            val width = (minOf(a.cx + a.w / 2, b.cx + b.w / 2) - maxOf(a.cx - a.w / 2, b.cx - b.w / 2)).coerceAtLeast(0f)
            val height = (minOf(a.cy + a.h / 2, b.cy + b.h / 2) - maxOf(a.cy - a.h / 2, b.cy - b.h / 2)).coerceAtLeast(0f)
            val intersection = width * height
            val union = a.w * a.h + b.w * b.h - intersection
            return if (union > 0) intersection / union else 0f
        }
    }
}
