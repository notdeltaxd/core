package com.maxrave.media3.player

/**
 * Volume curve shapes for transitions
 */
enum class Curve {
    /** Linear volume change */
    LINEAR,
    /** Exponential (fast start, slow end) volume change */
    EXP,
    /** Logarithmic (slow start, fast end) volume change */
    LOG,
    /** S-shaped (sigmoid) curve for a very smooth transition */
    S_CURVE
}

/**
 * Settings for a crossfade transition
 *
 * @param durationMs The duration of the transition in milliseconds
 * @param curveIn The curve to use for the incoming track's volume
 * @param curveOut The curve to use for the outgoing track's volume
 */
data class TransitionSettings(
    val durationMs: Int = 5000,
    val curveIn: Curve = Curve.S_CURVE,
    val curveOut: Curve = Curve.S_CURVE,
) {
    /**
     * Creates a copy with a new duration
     */
    fun copy(durationMs: Int): TransitionSettings = TransitionSettings(
        durationMs = durationMs,
        curveIn = curveIn,
        curveOut = curveOut,
    )
}
