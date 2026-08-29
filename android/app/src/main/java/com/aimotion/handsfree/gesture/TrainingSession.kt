package com.aimotion.handsfree.gesture

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates a recording session between the training screen and the running detection service.
 *
 * The screen does not open its own camera. The service already holds it, and two consumers of one
 * camera is a fight the training screen would lose — CameraX would have to unbind the service's
 * use case, stopping gesture control while the user records the gestures they want gesture
 * control to use. Instead the screen asks for N samples of one gesture and the service, which is
 * already receiving landmarks, fills the request.
 *
 * A process-wide singleton because the screen and the service genuinely are in one process (no
 * `android:process` in the manifest), and because the alternative — routing this through
 * SharedPreferences or a bound-service connection — would be ceremony around a single Gesture and
 * a counter.
 */
object TrainingSession {

    data class State(
        val target: Gesture? = null,
        val remaining: Int = 0,
        val captured: Int = 0,
        /** Set when a whole session finished, so the screen can say so once and then clear it. */
        val justCompleted: Gesture? = null,
    ) {
        val isRecording: Boolean get() = target != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Spacing between captured samples. Consecutive frames would be near-identical, and their
     * average would describe one instant of one hand position rather than the range the user
     * actually holds the gesture in — which is the entire reason for recording more than one.
     */
    private const val CAPTURE_INTERVAL_MS = 800L

    /** Grace before the first capture, so the user can get their hand into the pose after
     * tapping. */
    private const val LEAD_IN_MS = 1_200L

    @Volatile private var nextCaptureAtMs = 0L

    fun start(gesture: Gesture, sampleCount: Int) {
        nextCaptureAtMs = SystemClock.elapsedRealtime() + LEAD_IN_MS
        _state.value = State(target = gesture, remaining = sampleCount, captured = 0)
    }

    fun cancel() {
        _state.value = State()
    }

    fun acknowledgeCompletion() {
        _state.value = _state.value.copy(justCompleted = null)
    }

    /**
     * Called by the service for every frame containing a hand.
     *
     * @return the gesture to record this frame, or null if nothing should be recorded — either no
     *   session is running or the spacing interval has not elapsed.
     */
    fun gestureToCapture(): Gesture? {
        val current = _state.value
        val target = current.target ?: return null
        if (SystemClock.elapsedRealtime() < nextCaptureAtMs) return null
        return target
    }

    /** Called by the service after a frame was successfully recorded. */
    fun onCaptured() {
        val current = _state.value
        val target = current.target ?: return
        val remaining = current.remaining - 1
        nextCaptureAtMs = SystemClock.elapsedRealtime() + CAPTURE_INTERVAL_MS
        _state.value = if (remaining <= 0) {
            State(justCompleted = target)
        } else {
            current.copy(remaining = remaining, captured = current.captured + 1)
        }
    }
}
