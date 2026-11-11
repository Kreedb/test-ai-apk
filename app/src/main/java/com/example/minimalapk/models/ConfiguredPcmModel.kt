package com.example.minimalapk.models

import com.example.minimalapk.pose.GraphSignature

/** A serially owned interpreter over an explicitly configured raw-waveform input. */
internal class ConfiguredPcmModel(
    model: ConfiguredModel,
    private val config: ModelConfig,
    private val graph: GraphSignature,
) : AutoCloseable {
    val contract = checkNotNull(configuredInputContract(config, graph).pcm) { "没有可执行的单输入 PCM 协议" }
    private val owner = ConfiguredModelAccess.open(model).also {
        try { it.run { interpreter -> configureInputs(interpreter, config) } } catch (error: Throwable) { it.close(); throw error }
    }

    fun run(samples: FloatArray): ModelRunResult {
        require(samples.size == contract.samples) { "PCM 窗口长度与模型输入不符" }
        val values = DoubleArray(samples.size) { samples[it].toDouble() }
        return owner.run(discard = ModelRunResult::recycle) { interpreter ->
            execute(interpreter, config, graph,
                arrayOf(encodeTensor(values, graph.inputs.single(), InputSource.AUDIO)), emptyMap())
        }
    }

    fun cancel() = owner.cancel()
    override fun close() = owner.close()
}
