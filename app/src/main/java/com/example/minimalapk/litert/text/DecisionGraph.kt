/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

// Adapted from typed_decisions/DecisionGraph.kt in google-ai-edge/litert-samples@
// f9f0e000630e9cdd6d263c4cd80495e4231a2dc8. Changes: exact FLOAT32 signature-shape validation,
// full-input-write guards, finite readback and cancellation diagnostics. See ATTRIBUTION.md.
package com.example.minimalapk.litert.text

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Deliberately separate from the index-based common CompiledModelRunner: storage order and
 * signature order differ for these exports. Bind by name, verify the fixed float32 shape, and
 * require a full input overwrite for EVERY invocation. All five model cards require GPU FP32.
 */
internal class DecisionGraph private constructor(
    private val model: CompiledModel,
    private val inputs: Map<String, TensorBuffer>,
    private val outputs: Map<String, TensorBuffer>,
    private val inputShapes: Map<String, List<Int>>,
    private val outputShapes: Map<String, List<Int>>,
) : AutoCloseable {
    val inputNames: List<String> get() = inputs.keys.toList()
    private val written = HashSet<String>()
    private var hasOutput = false
    private var closed = false

    fun write(name: String, data: FloatArray) {
        check(!closed) { "Decision graph is closed." }
        val shape = inputShapes.getValue(name)
        require(data.size == shape.fold(1) { n, d -> n * d }) {
            "$name needs $shape, received ${data.size} float32 values."
        }
        require(data.all { it.isFinite() }) { "$name contains a non-finite value; check model resources." }
        hasOutput = false
        inputs.getValue(name).writeFloat(data)
        written.add(name)
    }

    fun run() {
        check(!closed) { "Decision graph is closed." }
        check(written == inputs.keys) { "Inputs not reset for this request: ${inputs.keys - written}" }
        written.clear()
        hasOutput = false
        if (Thread.currentThread().isInterrupted) throw CancellationException("Text decision cancelled.")
        model.run(inputs, outputs, SIGNATURE)
        hasOutput = true
    }

    /** readFloat synchronizes the GPU; callers include it in wall-clock timings. */
    fun read(name: String): FloatArray {
        check(!closed && hasOutput) { "Run the current request before reading its output." }
        val result = outputs.getValue(name).readFloat()
        val expected = outputShapes.getValue(name).fold(1) { n, d -> n * d }
        check(result.size == expected && result.all { it.isFinite() }) {
            "Model output $name must contain $expected finite float32 values; check graph/backend."
        }
        if (Thread.currentThread().isInterrupted) throw CancellationException("Text decision cancelled.")
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        written.clear()
        (inputs.values + outputs.values).forEach { runCatching { it.close() } }
        model.close()
    }

    companion object {
        private const val SIGNATURE = "serving_default"

        fun create(
            file: File,
            accelerator: Accelerator,
            inputs: Map<String, List<Int>>,
            outputs: Map<String, List<Int>>,
        ): DecisionGraph = create(file, accelerator, { inputs }, outputs)

        /** GLiNER-Decide's args_N names must be discovered by their shapes, not guessed. */
        fun create(
            file: File,
            accelerator: Accelerator,
            inputShapes: (dimensions: (String) -> List<Int>) -> Map<String, List<Int>>,
            outputShapes: Map<String, List<Int>>,
        ): DecisionGraph {
            require(file.isFile) { "Missing decision graph ${file.name}." }
            val options = CompiledModel.Options(accelerator).apply {
                if (accelerator == Accelerator.GPU) {
                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                } else cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
            }
            val model = CompiledModel.create(file.absolutePath, options, null)
            val inputs = linkedMapOf<String, TensorBuffer>()
            val outputs = linkedMapOf<String, TensorBuffer>()
            try {
                val shapes = inputShapes { name ->
                    model.getInputTensorType(name, SIGNATURE).layout?.dimensions.orEmpty()
                }
                shapes.forEach { (name, shape) ->
                    validateType(file.name, name, model.getInputTensorType(name, SIGNATURE), shape)
                    inputs[name] = model.createInputBuffer(name, SIGNATURE)
                }
                outputShapes.forEach { (name, shape) ->
                    validateType(file.name, name, model.getOutputTensorType(name, SIGNATURE), shape)
                    outputs[name] = model.createOutputBuffer(name, SIGNATURE)
                }
                return DecisionGraph(model, inputs, outputs, shapes, outputShapes)
            } catch (failure: Throwable) {
                (inputs.values + outputs.values).forEach { runCatching { it.close() } }
                runCatching { model.close() }
                throw failure
            }
        }

        private fun validateType(file: String, name: String, type: TensorType, expected: List<Int>) {
            require(type.elementType == TensorType.ElementType.FLOAT && type.layout?.dimensions == expected) {
                "$file/$SIGNATURE/$name: expected FLOAT32 $expected; got $type. " +
                    "Install the pinned fixed-window graph for this task."
            }
        }

        fun milliseconds(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0
    }
}

internal data class DecisionBackend<T>(val runner: T, val backend: String, val fallbackReason: String?)

internal fun <T> compileDecisionBackend(preferred: String, create: (Accelerator) -> T): DecisionBackend<T> {
    require(preferred in setOf("cpu", "gpu", "mixed")) { "Text decision backend must be cpu or gpu." }
    if (preferred == "cpu") return DecisionBackend(create(Accelerator.CPU), "CPU", null)
    return try {
        DecisionBackend(create(Accelerator.GPU), "GPU", null)
    } catch (failure: Exception) {
        if (failure is CancellationException || Thread.currentThread().isInterrupted) throw failure
        try {
            DecisionBackend(create(Accelerator.CPU), "CPU", "GPU FP32 compilation failed: ${failure.message}")
        } catch (cpuFailure: Exception) {
            cpuFailure.addSuppressed(failure)
            throw cpuFailure
        }
    }
}
