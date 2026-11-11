package com.example.minimalapk.plugins

import com.example.minimalapk.examples.SR_INPUT_SIZE
import com.example.minimalapk.examples.SR_OUTPUT_SIZE
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

internal const val SUPER_RESOLUTION_HALO = 5
internal const val SUPER_RESOLUTION_MAX_TILES = 96
internal const val SUPER_RESOLUTION_MAX_INPUT_EDGE = 512
internal const val SUPER_RESOLUTION_MAX_OUTPUT_EDGE = 2048

/** Shared publication predicate, evaluated before/after each native tile and by host on publish. */
internal fun superResolutionRequestIsCurrent(
    contextCurrent: Boolean,
    requested: PressToken,
    hostPress: PressToken?,
    localPress: PressToken?,
    source: ImageSource,
): Boolean = contextCurrent && requested == hostPress && requested == localPress &&
    source.session == requested.session && source.frameNumber >= requested.firstFrame

internal data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** Disjoint useful regions with a real-image halo on every inference; no center-only ROI. */
internal data class SuperResolutionTile(val core: PixelRect) {
    val sampleLeft: Int get() = core.left - SUPER_RESOLUTION_HALO
    val sampleTop: Int get() = core.top - SUPER_RESOLUTION_HALO
    val outputCrop: PixelRect get() = PixelRect(
        SUPER_RESOLUTION_HALO * SR_SCALE, SUPER_RESOLUTION_HALO * SR_SCALE,
        core.width * SR_SCALE, core.height * SR_SCALE,
    )
}

internal const val SR_SCALE = SR_OUTPUT_SIZE / SR_INPUT_SIZE

internal data class SuperResolutionPlan(val width: Int, val height: Int, val tiles: List<SuperResolutionTile>) {
    val outputWidth: Int get() = width * SR_SCALE
    val outputHeight: Int get() = height * SR_SCALE
}

// shortcut: the fixed CPU model uses a bounded whole-photo working size; upgrade for native-resolution real-time SR.
/** Bounds both native calls and output memory, uniformly resampling the whole photo when needed. */
internal fun superResolutionPlan(width: Int, height: Int): SuperResolutionPlan {
    require(width > 0 && height > 0)
    val core = SR_INPUT_SIZE - SUPER_RESOLUTION_HALO * 2
    fun dimensions(scale: Double) = Pair(
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
    )
    fun fits(scale: Double): Boolean {
        val (w, h) = dimensions(scale)
        return (w + core - 1) / core * ((h + core - 1) / core) <= SUPER_RESOLUTION_MAX_TILES
    }
    var upper = min(1.0, SUPER_RESOLUTION_MAX_INPUT_EDGE.toDouble() / maxOf(width, height))
    if (!fits(upper)) {
        var lower = 0.0
        repeat(40) {
            val middle = (lower + upper) / 2
            if (fits(middle)) lower = middle else upper = middle
        }
        upper = lower
    }
    val (w, h) = dimensions(upper)
    val tiles = buildList {
        for (y in 0 until h step core) for (x in 0 until w step core) {
            add(SuperResolutionTile(PixelRect(x, y, min(core, w - x), min(core, h - y))))
        }
    }
    return SuperResolutionPlan(w, h, tiles)
}

/** Exact host aspect ratio, bounded to 2048², without magnifying past the inferred photo pixels. */
internal fun superResolutionCompositeSize(space: ImageSpace, plan: SuperResolutionPlan): Pair<Int, Int> {
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val divisor = gcd(space.width, space.height)
    val bounds = space.photoBounds
    val scale = minOf(
        plan.outputWidth / (bounds.right - bounds.left),
        plan.outputHeight / (bounds.bottom - bounds.top),
        SUPER_RESOLUTION_MAX_OUTPUT_EDGE.toFloat() / space.width,
        SUPER_RESOLUTION_MAX_OUTPUT_EDGE.toFloat() / space.height,
    )
    val units = floor(divisor * scale).toInt()
    require(units > 0) { "当前预览宽高比无法在整帧超分内存上限内合成" }
    return space.width / divisor * units to space.height / divisor * units
}
