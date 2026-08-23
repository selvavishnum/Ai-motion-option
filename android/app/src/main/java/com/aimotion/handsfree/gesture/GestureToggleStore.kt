package com.aimotion.handsfree.gesture

import android.content.Context
import android.content.SharedPreferences

/**
 * Which detectors are switched on. Beyond being a user preference, this directly controls
 * responsiveness: the camera feed is a fixed budget of frames per second, and when both
 * detectors are on they alternate, so each one only sees every other frame. Turning one off
 * hands the other **all** the frames, halving the time it takes a gesture to be recognised —
 * see GestureControlService's frame scheduling.
 *
 * Values are mirrored in memory because the frame loop reads them on **every** frame, and each
 * `SharedPreferences` getter takes a lock on the shared map. Volatile fields updated from the
 * change listener keep reads free while still reflecting an edit made in Settings immediately.
 */
class GestureToggleStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_toggles", Context.MODE_PRIVATE)

    @Volatile private var handCache = prefs.getBoolean(KEY_HAND, true)
    @Volatile private var faceCache = prefs.getBoolean(KEY_FACE, true)
    @Volatile private var bubbleCache = prefs.getBoolean(KEY_BUBBLE, true)

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so one with no strong
    // reference is collected and silently stops firing.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_HAND -> handCache = p.getBoolean(KEY_HAND, true)
            KEY_FACE -> faceCache = p.getBoolean(KEY_FACE, true)
            KEY_BUBBLE -> bubbleCache = p.getBoolean(KEY_BUBBLE, true)
            // key is null when preferences are cleared wholesale; re-read everything.
            null -> {
                handCache = p.getBoolean(KEY_HAND, true)
                faceCache = p.getBoolean(KEY_FACE, true)
                bubbleCache = p.getBoolean(KEY_BUBBLE, true)
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    var handEnabled: Boolean
        get() = handCache
        set(value) {
            // Written through immediately as well as via the listener: apply() is asynchronous
            // and its callback lands later, so a read in between would serve the old value.
            handCache = value
            prefs.edit().putBoolean(KEY_HAND, value).apply()
        }

    var faceEnabled: Boolean
        get() = faceCache
        set(value) {
            faceCache = value
            prefs.edit().putBoolean(KEY_FACE, value).apply()
        }

    /** Whether to show the floating status dot over other apps. Separate from the overlay
     * permission itself, so hiding the dot doesn't mean revoking a permission the user may want
     * to keep granted. */
    var bubbleEnabled: Boolean
        get() = bubbleCache
        set(value) {
            bubbleCache = value
            prefs.edit().putBoolean(KEY_BUBBLE, value).apply()
        }

    private companion object {
        const val KEY_HAND = "hand_enabled"
        const val KEY_FACE = "face_enabled"
        const val KEY_BUBBLE = "bubble_enabled"
    }
}
