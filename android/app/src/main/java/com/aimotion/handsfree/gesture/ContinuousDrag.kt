package com.aimotion.handsfree.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper

/**
 * Holds a touch down and drags it along an arbitrary path.
 *
 * This is the piece that makes a curve possible at all. A `dispatchGesture` of a finished stroke
 * is a complete touch — down, move, up — so a shape built from them is a series of separate taps
 * and flicks, never one continuous line. Android's answer is a stroke marked `willContinue`,
 * extended by `continueStroke` as often as you like and closed by a final segment that is not
 * marked to continue. That chain is one unbroken touch, which is what lets a circle traced in
 * the air arrive in the app underneath as a circle.
 *
 * ## Why the queue
 *
 * A stroke can only be continued after the segment before it has finished; dispatching the next
 * one early fails, and the whole drag dies with it. Camera frames do not wait for that. So this
 * keeps exactly one segment in flight and remembers only the **latest** point requested while it
 * is busy — deliberately dropping the ones in between rather than queueing them. A backlog would
 * make the cursor lag further behind the finger the faster you moved, which is the opposite of
 * what a pointer should do; skipping to the newest point keeps the drawn line attached to the
 * fingertip, at the cost of a slightly coarser path when the hand outruns the dispatcher.
 *
 * All state is confined to the main thread. Camera frames arrive on the analysis thread, so every
 * entry point posts, and nothing here needs a lock.
 */
class ContinuousDrag(private val dispatch: (GestureDescription, Runnable, Runnable) -> Boolean) {

    private val handler = Handler(Looper.getMainLooper())

    private var active = false
    private var inFlight = false
    private var endRequested = false
    private var lastX = 0f
    private var lastY = 0f
    private var pendingX = 0f
    private var pendingY = 0f
    private var hasPending = false
    private var stroke: GestureDescription.StrokeDescription? = null

    val isActive: Boolean get() = active

    fun start(x: Float, y: Float) = handler.post {
        if (active) return@post
        active = true
        endRequested = false
        hasPending = false
        lastX = x
        lastY = y

        val path = Path().apply { moveTo(x, y) }
        val first = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
        stroke = first
        send(first)
    }

    fun moveTo(x: Float, y: Float) = handler.post {
        if (!active || endRequested) return@post
        pendingX = x
        pendingY = y
        hasPending = true
        if (!inFlight) pump()
    }

    /**
     * Ends the drag, lifting the touch.
     *
     * The lift is queued rather than performed, because a stroke cannot be closed while a segment
     * of it is still in flight. Marking the intent and letting [pump] close the chain is what
     * keeps the final touch-up from being dropped — and a drag that never lifts would leave the
     * app underneath believing a finger is still pressed.
     */
    fun end(x: Float, y: Float) = handler.post {
        if (!active) return@post
        pendingX = x
        pendingY = y
        hasPending = true
        endRequested = true
        if (!inFlight) pump()
    }

    /** Abandons the drag without dispatching anything further. For teardown only: prefer [end],
     * which actually lifts the touch. */
    fun cancel() = handler.post { reset() }

    private fun pump() {
        val current = stroke
        if (current == null || !active) {
            reset()
            return
        }
        if (!hasPending) {
            if (endRequested) closeWith(current, lastX, lastY)
            return
        }

        val x = pendingX
        val y = pendingY
        hasPending = false

        if (endRequested) {
            closeWith(current, x, y)
            return
        }

        val next = current.continueStroke(segmentPath(x, y), 0, SEGMENT_MS, true)
        lastX = x
        lastY = y
        stroke = next
        send(next)
    }

    private fun closeWith(current: GestureDescription.StrokeDescription, x: Float, y: Float) {
        val closing = current.continueStroke(segmentPath(x, y), 0, SEGMENT_MS, false)
        stroke = null
        active = false
        endRequested = false
        hasPending = false
        send(closing)
    }

    private fun segmentPath(x: Float, y: Float) = Path().apply {
        moveTo(lastX, lastY)
        lineTo(x, y)
    }

    private fun send(strokeDescription: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(strokeDescription).build()
        inFlight = true
        // Both outcomes must clear the in-flight flag. A cancelled segment that left it set would
        // strand the drag: no further segment would ever be sent, and the touch would stay down
        // until something else reset it.
        val onDone = Runnable {
            inFlight = false
            if (active) pump()
        }
        val onCancelled = Runnable {
            inFlight = false
            reset()
        }
        if (!dispatch(gesture, onDone, onCancelled)) {
            inFlight = false
            reset()
        }
    }

    private fun reset() {
        active = false
        inFlight = false
        endRequested = false
        hasPending = false
        stroke = null
    }

    private companion object {
        /** Each segment's duration. Matched to the camera's frame budget: shorter would ask the
         * dispatcher for more segments than frames exist to fill, longer would make the drawn
         * line visibly trail the fingertip. */
        const val SEGMENT_MS = 60L
    }
}
