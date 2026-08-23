package com.aimotion.handsfree.gesture

import android.content.Context

/** Which detectors are switched on. Beyond being a user preference, this directly controls
 * responsiveness: the camera feed is a fixed budget of frames per second, and when both
 * detectors are on they alternate, so each one only sees every other frame. Turning one off
 * hands the other **all** the frames, halving the time it takes a gesture to be recognised —
 * see GestureControlService's frame scheduling. */
class GestureToggleStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_toggles", Context.MODE_PRIVATE)

    var handEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAND, true)
        set(value) = prefs.edit().putBoolean(KEY_HAND, value).apply()

    var faceEnabled: Boolean
        get() = prefs.getBoolean(KEY_FACE, true)
        set(value) = prefs.edit().putBoolean(KEY_FACE, value).apply()

    private companion object {
        const val KEY_HAND = "hand_enabled"
        const val KEY_FACE = "face_enabled"
    }
}
