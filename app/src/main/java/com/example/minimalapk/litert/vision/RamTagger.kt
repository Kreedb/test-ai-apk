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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Model Zoo RAM++ four-graph pipeline. Stage 3 and reweight ALWAYS stay on CPU, avoiding the
 * stage-3 fp16 accumulation failure and the large frozen tag bank on the GPU.
 */
internal class RamTagger(private val directory: File, accelerator: Accelerator) : AutoCloseable {
    private val tags = File(directory, "ram_tag_list.txt").readLines().also { require(it.size == NCLASS) }
    private val thresholds = thresholds(File(directory, "ram_tag_threshold.bin").readBytes()).also { require(it.size == NCLASS) }
    private val resources = mutableListOf<AutoCloseable>()

    private fun load(name: String, backend: Accelerator): CompiledModelRunner = try {
        CompiledModelRunner.fromFile(File(directory, name).absolutePath, backend).also { resources.add(it) }
    } catch (failure: Throwable) {
        close()
        throw failure
    }

    private val swin = load("ram_swin_s012_fp16.tflite", accelerator)
    private val tail = load("ram_stage3_tail_fp16.tflite", Accelerator.CPU)
    private val reweight = load("ram_reweight_fp16.tflite", Accelerator.CPU)
    private val head = load("ram_taghead_fp16.tflite", accelerator)
    private val querySlot: Int
    private val imageSlot: Int

    init {
        try {
            querySlot = head.inputBuffers.indexOfFirst { it.readFloat().size == NCLASS * 768 }
            imageSlot = head.inputBuffers.indexOfFirst { it.readFloat().size == 145 * 512 }
            check(querySlot >= 0 && imageSlot >= 0 && querySlot != imageSlot) { "Unexpected RAM++ tag-head inputs" }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun tag(bitmap: Bitmap): List<Tag> {
        swin.writeInput(0, VisionMath.imageNetChw(resizedPixels(bitmap, 384)))
        swin.run()
        tail.writeInput(0, swin.readOutput(0).also { check(it.size == 144 * 1536) })
        tail.run()
        val imageEmbeddings = tail.readOutput(0).also { check(it.size == 145 * 512) }
        reweight.writeInput(0, imageEmbeddings.copyOfRange(0, 512)) // CLS token
        reweight.run()
        head.writeInput(querySlot, reweight.readOutput(0).also { check(it.size == NCLASS * 768) })
        head.writeInput(imageSlot, imageEmbeddings)
        head.run()
        return selectTags(head.readOutput(0), tags, thresholds, 40)
    }

    override fun close() {
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    data class Tag(val index: Int, val name: String, val probability: Float, val threshold: Float)

    companion object {
        const val NCLASS = 4585
        fun thresholds(bytes: ByteArray): FloatArray {
            require(bytes.isNotEmpty() && bytes.size % 4 == 0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.float }.also {
                require(it.all { threshold -> threshold.isFinite() && threshold in 0f..1f })
            }
        }

        fun selectTags(logits: FloatArray, tags: List<String>, thresholds: FloatArray, topK: Int): List<Tag> {
            require(logits.size == tags.size && thresholds.size == tags.size && topK >= 0)
            require(logits.all { it.isFinite() }) { "Non-finite RAM++ logits" }
            return logits.indices.mapNotNull { i ->
                val probability = (1.0 / (1.0 + exp(-logits[i].toDouble()))).toFloat()
                if (probability > thresholds[i]) Tag(i, tags[i], probability, thresholds[i]) else null
            }.sortedByDescending { it.probability }.take(topK)
        }
    }
}
