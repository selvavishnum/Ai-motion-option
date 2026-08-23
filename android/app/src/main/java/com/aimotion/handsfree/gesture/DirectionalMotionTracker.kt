package com.aimotion.handsfree.gesture

import kotlin.math.abs

enum class Direction { LEFT, RIGHT, UP, DOWN }

/**
 * Turns a stream of noisy 2D landmark positions into discrete direction events.
 *
 * Replaces frame-to-frame delta comparison, which had three problems that showed up directly as
 * "it moves the wrong way" and "it misses my movement":
 *
 * 1. **A single frame's delta is mostly jitter.** Landmark positions wobble by a similar
 *    magnitude to a slow deliberate movement, so the sign of one frame's delta is close to a coin
 *    flip at low speeds. Positions are now low-pass filtered before anything is decided.
 *
 * 2. **Movement during a cooldown used to be thrown away.** The previous position was updated
 *    every frame while the cooldown suppressed firing, so only the last frame's fraction of the
 *    motion survived. Displacement is now measured from an anchor that moves *only when an event
 *    fires*, so every bit of travel counts and slow deliberate moves accumulate instead of being
 *    filtered out as noise.
 *
 * 3. **A diagonal move fired a confident direction.** Comparing `|dx| > |dy|` picks a winner even
 *    when the two are nearly equal. One axis must now clearly dominate, so an ambiguous move
 *    produces nothing rather than a guess.
 *
 * @param moveThreshold displacement, in the caller's units, required before a direction fires.
 * @param smoothing low-pass factor in 0..1. Lower is steadier but laggier; 0.5 halves the jitter
 *   while staying responsive within a couple of frames.
 * @param axisDominance how much the winning axis must exceed the other. 1.0 accepts any diagonal.
 */
class DirectionalMotionTracker(
    private val moveThreshold: Float,
    private val smoothing: Float = 0.5f,
    private val axisDominance: Float = 1.5f,
) {
    private var hasPosition = false
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var anchorX = 0f
    private var anchorY = 0f

    /** Consecutive updates during which the subject has stayed near the anchor. Used for
     * hold-to-tap: "still" means it hasn't travelled, not that consecutive frames matched. */
    var stillUpdates: Int = 0
        private set

    fun reset() {
        hasPosition = false
        stillUpdates = 0
    }

    /**
     * Feeds one observation.
     *
     * @param scale a size reference — interocular distance, hand span — used to make the
     *   threshold independent of how close the subject is to the camera. Pass 1f for raw
     *   normalized-image units.
     * @return the direction travelled since the last event, or null if it hasn't travelled far
     *   enough or the movement is too diagonal to call.
     */
    fun update(x: Float, y: Float, scale: Float = 1f): Direction? {
        if (!hasPosition) {
            smoothedX = x
            smoothedY = y
            anchorX = x
            anchorY = y
            hasPosition = true
            stillUpdates = 0
            return null
        }

        smoothedX += smoothing * (x - smoothedX)
        smoothedY += smoothing * (y - smoothedY)

        val safeScale = if (scale > 1e-4f) scale else 1e-4f
        val dx = (smoothedX - anchorX) / safeScale
        val dy = (smoothedY - anchorY) / safeScale
        val absX = abs(dx)
        val absY = abs(dy)

        if (absX < moveThreshold && absY < moveThreshold) {
            stillUpdates++
            return null
        }

        stillUpdates = 0

        val direction = when {
            absX >= moveThreshold && absX > absY * axisDominance ->
                if (dx > 0) Direction.RIGHT else Direction.LEFT
            absY >= moveThreshold && absY > absX * axisDominance ->
                if (dy > 0) Direction.DOWN else Direction.UP
            // Travelled far enough, but too diagonal to attribute to one axis. Re-anchor so the
            // next reading is measured from here rather than compounding into a false confident
            // direction.
            else -> {
                anchorX = smoothedX
                anchorY = smoothedY
                return null
            }
        }

        anchorX = smoothedX
        anchorY = smoothedY
        return direction
    }
}
