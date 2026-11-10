package com.example.minimalapk.pose

/**
 * Presence hysteresis for one model: the published state flips only after [frames] consecutive
 * frame results agree, so a single-frame false positive or dropout does not flicker the overlay.
 * [frames] <= 1 publishes every frame unchanged.
 */
internal class PoseDebounce(private val frames: Int) {
    private var published = false
    private var run = 0

    fun accept(detected: Boolean): Boolean {
        if (detected == published) {
            run = 0
            return published
        }
        if (++run >= frames.coerceAtLeast(1)) {
            published = detected
            run = 0
        }
        return published
    }
}
