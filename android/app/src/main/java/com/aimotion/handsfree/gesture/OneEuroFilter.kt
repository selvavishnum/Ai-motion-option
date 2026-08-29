package com.aimotion.handsfree.gesture

import kotlin.math.PI
import kotlin.math.abs

/**
 * Port of the Python reference implementation (app/one_euro.py in the repo root), whose tests
 * pin the numerical behaviour.
 *
 * The air pointer used a single fixed low-pass factor, and a fixed factor cannot be right. Set it
 * heavy enough that a still fingertip stops buzzing and the cursor lags visibly behind a fast
 * movement; set it light enough to keep up and the dot jitters when you try to hold it on a
 * target. Both failures read to the user as "it doesn't work properly".
 *
 * The 1-Euro filter (Casiez, Roussel & Vogel, CHI 2012) makes the cutoff a function of speed:
 * heavy smoothing while the hand is nearly still, so the dot sits quietly on its target, and
 * progressively lighter smoothing as it moves, so a fast sweep is not dragged behind. It is two
 * one-pole low-pass filters — one on the value, one on its derivative — which is why it fits
 * inside a per-frame budget on a phone.
 */

/**
 * Defaults tuned for a fingertip in normalized image coordinates.
 *
 * [DEFAULT_BETA] is scaled to the units it multiplies. Speed here is in normalized image widths
 * per second, where a brisk sweep across the frame is under 1.0 — so the textbook values quoted
 * for pixel coordinates (fractions of a hundredth) would add nothing to the cutoff at any speed
 * a hand can reach, leaving a filter that is adaptive in name only.
 */
const val DEFAULT_MIN_CUTOFF = 0.7f
const val DEFAULT_BETA = 1.0f
const val DEFAULT_D_CUTOFF = 1.0f

/** Guards a duplicated or out-of-order timestamp against producing a division by zero or a
 * nonsensical rate. Far above any camera frame rate this runs at. */
private const val MAX_RATE_HZ = 200.0f

private fun alphaFor(cutoff: Float, dt: Float): Float {
    val tau = 1.0f / (2.0f * PI.toFloat() * cutoff)
    return 1.0f / (1.0f + tau / dt)
}

/** One-pole low-pass filter that remembers whether it has seen a value yet. */
private class LowPass {
    var value: Float = 0f
        private set
    private var initialised = false

    fun filter(x: Float, alpha: Float): Float {
        if (!initialised) {
            value = x
            initialised = true
            return x
        }
        value = alpha * x + (1f - alpha) * value
        return value
    }

    fun reset() {
        initialised = false
        value = 0f
    }
}

/**
 * Smooths one scalar signal. Use one per axis.
 *
 * @param minCutoff cutoff frequency, in Hz, at zero speed. Lower means a steadier cursor when the
 *   hand is still, at the cost of more lag when it starts moving.
 * @param beta how much the cutoff rises with speed. Higher means the filter yields sooner to a
 *   deliberate movement.
 * @param dCutoff cutoff for the speed estimate itself. It exists so the speed used to pick the
 *   cutoff is not itself pure noise.
 */
class OneEuroFilter(
    var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    var beta: Float = DEFAULT_BETA,
    private val dCutoff: Float = DEFAULT_D_CUTOFF,
) {
    private val x = LowPass()
    private val dx = LowPass()
    private var lastTimestampMs: Long = NO_TIMESTAMP

    /** Forgets all history. Call when the tracked subject disappears, so the next sighting starts
     * at its own position rather than sliding in from where the last one ended. */
    fun reset() {
        x.reset()
        dx.reset()
        lastTimestampMs = NO_TIMESTAMP
    }

    fun filter(value: Float, timestampMs: Long): Float {
        val previous = lastTimestampMs
        lastTimestampMs = timestampMs

        if (previous == NO_TIMESTAMP) {
            // No interval yet, so no speed and nothing to smooth against.
            x.filter(value, 1f)
            return value
        }

        val elapsedS = (timestampMs - previous) / 1000f
        // A non-advancing clock would make the rate infinite and the filter a pass-through; a
        // backwards one would make it negative. Clamping is the honest response to both: treat
        // them as one tick at the ceiling rate.
        val dt = if (elapsedS > 1f / MAX_RATE_HZ) elapsedS else 1f / MAX_RATE_HZ

        val speed = (value - x.value) / dt
        val smoothedSpeed = dx.filter(speed, alphaFor(dCutoff, dt))

        return x.filter(value, alphaFor(minCutoff + beta * abs(smoothedSpeed), dt))
    }

    private companion object {
        const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}

/**
 * Two [OneEuroFilter]s sharing a configuration, for a point.
 *
 * The axes are filtered independently but from the same clock, so a diagonal movement is smoothed
 * by the same amount on both — which is what keeps a curve drawn in the air smooth instead of
 * squashed along whichever axis happened to be noisier.
 */
class OneEuroFilter2D(
    minCutoff: Float = DEFAULT_MIN_CUTOFF,
    beta: Float = DEFAULT_BETA,
    dCutoff: Float = DEFAULT_D_CUTOFF,
) {
    private val fx = OneEuroFilter(minCutoff, beta, dCutoff)
    private val fy = OneEuroFilter(minCutoff, beta, dCutoff)

    /** Rewritten when the sensitivity dial changes, on a different thread from the one filtering
     * frames — hence assigning to both filters rather than holding one shared field. */
    fun configure(minCutoff: Float, beta: Float) {
        fx.minCutoff = minCutoff
        fx.beta = beta
        fy.minCutoff = minCutoff
        fy.beta = beta
    }

    fun reset() {
        fx.reset()
        fy.reset()
    }

    fun filterX(value: Float, timestampMs: Long): Float = fx.filter(value, timestampMs)

    fun filterY(value: Float, timestampMs: Long): Float = fy.filter(value, timestampMs)
}
