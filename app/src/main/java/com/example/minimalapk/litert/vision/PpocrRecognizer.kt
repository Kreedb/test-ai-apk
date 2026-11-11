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

import com.example.minimalapk.litert.CompiledModelRunner
import com.google.ai.edge.litert.Accelerator
import java.io.File

/** PP-OCRv5 PPLCNetV3/SVTR CTC: NCHW 48×320, (RGB/255 - .5)/.5, blank + dict + space. */
internal class PpocrRecognizer(model: File, dictionary: File, accelerator: Accelerator) : AutoCloseable {
    private val chars = characters(dictionary.readLines().also {
        require(it.size == 18383) { "PP-OCRv5 requires the pinned 18383-character dictionary" }
    })
    private val runner = CompiledModelRunner.fromFile(model.absolutePath, accelerator)

    fun recognize(rgb: FloatArray): Recognition {
        require(rgb.size == H * W * 3)
        val hw = H * W
        val chw = FloatArray(hw * 3) { index -> (rgb[(index % hw) * 3 + index / hw] / 255f - 0.5f) / 0.5f }
        runner.writeInput(0, chw)
        runner.run()
        return decode(runner.readOutput(0), chars)
    }

    data class Recognition(val text: String, val confidence: Float, val tokenCount: Int)
    override fun close() = runner.close()

    companion object {
        const val MODEL = "ppocr_rec_fp16.tflite"
        const val DICT = "ppocrv5_dict.txt"
        const val H = 48
        const val W = 320

        fun characters(dictionary: List<String>): List<String> = listOf("") + dictionary + listOf(" ")

        /**
         * The pinned graph ends in SOFTMAX (op 826), although upstream calls its output "logits".
         * Do not apply softmax a second time: confidence is the mean of emitted token probabilities.
         */
        fun decode(probabilities: FloatArray, chars: List<String>): Recognition {
            require(chars.size > 1 && chars[0].isEmpty())
            require(probabilities.isNotEmpty() && probabilities.size % chars.size == 0)
            require(probabilities.all { it.isFinite() && it >= 0f && it <= 1.001f }) { "Invalid OCR softmax output" }
            val text = StringBuilder()
            var previous = -1
            var count = 0
            var sum = 0.0
            for (time in 0 until probabilities.size / chars.size) {
                val base = time * chars.size
                var best = 0
                for (c in 1 until chars.size) if (probabilities[base + c] > probabilities[base + best]) best = c
                if (best != previous && best != 0) {
                    text.append(chars[best])
                    sum += probabilities[base + best].coerceIn(0f, 1f)
                    count++
                }
                // A blank separates repeated letters ("book" must not collapse to "bok").
                previous = best
            }
            return Recognition(text.toString(), if (count > 0) (sum / count).toFloat() else 0f, count)
        }
    }
}
