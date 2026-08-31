package com.aimotion.handsfree.gesture

import kotlin.math.hypot

/**
 * Port of the Python reference implementation (app/air_pointer.py in the repo root), whose tests
 * pin the state machine.
 *
 * ## What this replaces
 *
 * The old single-finger behaviour fired one of four fixed swipes once the fingertip had travelled
 * far enough along an axis. That is why a finger in the air could draw a "+" and nothing else:
 * every movement was forced onto the nearest axis and quantised into a fixed-length swipe, so a
 * circle came out as, at best, four straight strokes. Curves were not merely inaccurate; they
 * were not representable.
 *
 * ## What replaces it
 *
 * A cursor that follows the fingertip continuously, plus a pen that can be put down and lifted.
 * While the pen is down the cursor's whole path is dispatched as one continuous drag, so whatever
 * the finger traces — a circle, an arc, a long smooth scroll — arrives as that shape. Scrolling
 * and turning are then not special cases at all: they are drags, of whatever length the user
 * actually made.
 *
 * ## Putting the pen down
 *
 * Holding the fingertip still toggles it: dwell to press, dwell again to lift. Dwell is the
 * established idiom for pointers with no buttons — head- and eye-tracking mice both use it — and
 * it is the only signal available from a single finger that does not require a second pose,
 * which is the constraint here.
 *
 * Three rules stop it from being twitchy:
 *
 * 1. The dwell timer only runs while the cursor stays inside a small radius.
 * 2. It also only runs while the cursor is slower than a walking pace. A radius alone is not
 *    enough: a hand drawing *slowly* creeps across the radius over several frames without any one
 *    frame looking like movement, so a long careful stroke could put the pen down in the middle
 *    of itself. Speed catches that immediately, at any dwell length — which matters because the
 *    sensitivity dial shortens the dwell.
 * 3. After a toggle fires, another cannot fire until the finger has moved again. Without this a
 *    resting hand would toggle the pen down, up, down, ... once per dwell interval, which is
 *    exactly the "it does things I didn't ask for" failure this is meant to fix.
 */

/** Your hand travels comfortably across the middle of the camera's view, so that band is
 * stretched to cover the whole screen. Mapping the full frame would mean reaching far to one side
 * just to touch the edge of the display, and never quite reaching the corners. */
private const val ACTIVE_LO = 0.2f
private const val ACTIVE_HI = 0.8f

/** Baselines for sensitivity level 3; [SensitivityStore] rescales them. */
const val DEFAULT_DWELL_MS = 700L
const val DEFAULT_DWELL_RADIUS_PX = 34f
const val DEFAULT_STILL_SPEED_PX_S = 70f

/** What the caller must do about this frame, beyond moving the dot. */
enum class PointerEvent {
    NONE,

    /** Begin a drag at the reported position. */
    PEN_DOWN,

    /** Extend the drag in progress to the reported position. */
    PEN_MOVE,

    /** End the drag. */
    PEN_UP,
}

data class PointerUpdate(val x: Float, val y: Float, val penDown: Boolean, val event: PointerEvent)

/** Turns a stream of fingertip observations into cursor positions and pen events. */
class AirPointer(
    private var widthPx: Int,
    private var heightPx: Int,
    var dwellMs: Long = DEFAULT_DWELL_MS,
    var dwellRadiusPx: Float = DEFAULT_DWELL_RADIUS_PX,
    var stillSpeedPxPerSecond: Float = DEFAULT_STILL_SPEED_PX_S,
) {
    private val filter = OneEuroFilter2D()

    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var penDown: Boolean = false
        private set

    private var hasPosition = false
    private var dwellX = 0f
    private var dwellY = 0f
    private var dwellSinceMs = 0L
    private var lastTimestampMs = 0L

    /** Set when a toggle fires; cleared once the finger moves again. */
    private var toggleLatched = false

    /** The display can change under a rotation, and a cursor mapped to the old one would sit off
     * the edge of the new. */
    fun resize(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
    }

    fun configureFilter(minCutoff: Float, beta: Float) = filter.configure(minCutoff, beta)

    /** Feeds one fingertip observation in normalized (0..1) image coordinates. */
    fun update(rawX: Float, rawY: Float, timestampMs: Long): PointerUpdate {
        val newX = mapAxis(filter.filterX(rawX, timestampMs), widthPx)
        val newY = mapAxis(filter.filterY(rawY, timestampMs), heightPx)

        if (!hasPosition) {
            hasPosition = true
            x = newX
            y = newY
            lastTimestampMs = timestampMs
            beginDwell(timestampMs)
            return PointerUpdate(x, y, penDown, PointerEvent.NONE)
        }

        val elapsedS = ((timestampMs - lastTimestampMs) / 1000f).coerceAtLeast(1e-3f)
        val speed = hypot(newX - x, newY - y) / elapsedS
        lastTimestampMs = timestampMs
        x = newX
        y = newY

        val moving = hypot(x - dwellX, y - dwellY) > dwellRadiusPx || speed > stillSpeedPxPerSecond
        if (moving) {
            // Moving is the common case, and it clears everything the dwell was accumulating —
            // including the latch, so the *next* stop can toggle again.
            beginDwell(timestampMs)
            toggleLatched = false
            return PointerUpdate(x, y, penDown, moveEvent())
        }

        if (toggleLatched || timestampMs - dwellSinceMs < dwellMs) {
            // Still holding, but either not for long enough yet or already spent on a toggle. A
            // drag still needs the position: a pen held almost still is drawing a small mark,
            // not nothing.
            return PointerUpdate(x, y, penDown, moveEvent())
        }

        toggleLatched = true
        penDown = !penDown
        return PointerUpdate(x, y, penDown, if (penDown) PointerEvent.PEN_DOWN else PointerEvent.PEN_UP)
    }

    /**
     * Ends a drag but keeps the cursor exactly where it is.
     *
     * Distinct from [release], and the distinction matters: release forgets the smoothing history
     * so a hand that has left and come back does not slide in from where the last one stopped.
     * Doing that after every click would restart the filter mid-session, and the cursor would
     * visibly jump the moment the user selected something — while they were looking at the thing
     * they had just carefully aimed at.
     */
    fun liftPen(): PointerUpdate? {
        if (!penDown) return null
        penDown = false
        return PointerUpdate(x, y, false, PointerEvent.PEN_UP)
    }

    /**
     * Call when the finger is no longer visible.
     *
     * Returns a PEN_UP if a drag was in progress — a stroke left open because a hand left the
     * frame would keep the touch pressed on whatever is underneath, which is worse than any
     * gesture this could have been. Returns null if there was nothing to end.
     */
    fun release(): PointerUpdate? {
        val wasDown = penDown
        val lastX = x
        val lastY = y
        filter.reset()
        hasPosition = false
        penDown = false
        toggleLatched = false
        return if (wasDown) PointerUpdate(lastX, lastY, false, PointerEvent.PEN_UP) else null
    }

    private fun moveEvent() = if (penDown) PointerEvent.PEN_MOVE else PointerEvent.NONE

    private fun beginDwell(timestampMs: Long) {
        dwellX = x
        dwellY = y
        dwellSinceMs = timestampMs
    }

    /** Stretches the comfortable middle band of the camera view across a full screen axis. */
    private fun mapAxis(normalized: Float, sizePx: Int): Float =
        ((normalized - ACTIVE_LO) / (ACTIVE_HI - ACTIVE_LO)).coerceIn(0f, 1f) * sizePx
}
