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

package com.example.minimalapk.litert

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

/** Adapted from litert-samples Model Zoo common/CompiledModelRunner.kt (f9f0e000).
 * The owner must compile, run/read back, and close on the same worker thread. GPU run() can
 * enqueue work; readOutput() completes the readback and must be included in timings.
 */
class CompiledModelRunner private constructor(private val model: CompiledModel) : AutoCloseable {
    val inputBuffers: List<TensorBuffer>
    val outputBuffers: List<TensorBuffer>

    init {
        var inputs: List<TensorBuffer>? = null
        try {
            inputs = model.createInputBuffers()
            inputBuffers = inputs
            outputBuffers = model.createOutputBuffers()
        } catch (failure: Throwable) {
            inputs?.forEach { runCatching { it.close() } }
            runCatching { model.close() }
            throw failure
        }
    }

    companion object {
        fun fromAssets(
            context: Context,
            fileName: String,
            vararg accelerators: Accelerator = arrayOf(Accelerator.GPU),
        ): CompiledModelRunner = CompiledModelRunner(
            CompiledModel.create(context.assets, fileName, CompiledModel.Options(*accelerators), null),
        )

        fun fromFile(
            path: String,
            vararg accelerators: Accelerator = arrayOf(Accelerator.GPU),
        ): CompiledModelRunner = fromFile(path, CompiledModel.Options(*accelerators), null)

        fun fromFile(
            path: String,
            options: CompiledModel.Options,
            environment: Environment?,
        ): CompiledModelRunner = CompiledModelRunner(CompiledModel.create(path, options, environment))
    }

    fun writeInput(index: Int, data: FloatArray) {
        inputBuffers[index].writeFloat(data)
    }

    fun run() {
        model.run(inputBuffers, outputBuffers)
    }

    fun run(inputs: List<TensorBuffer>, outputs: List<TensorBuffer>) {
        model.run(inputs, outputs)
    }

    fun readOutput(index: Int): FloatArray = outputBuffers[index].readFloat()

    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers.forEach { runCatching { it.close() } }
        model.close()
    }
}
