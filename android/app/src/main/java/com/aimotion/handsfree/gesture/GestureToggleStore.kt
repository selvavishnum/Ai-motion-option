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
    @Volatile private var pauseScreenOffCache = prefs.getBoolean(KEY_PAUSE_SCREEN_OFF, true)
    @Volatile private var waveCache = prefs.getBoolean(KEY_WAVE, true)
    @Volatile private var pointerCache = prefs.getBoolean(KEY_POINTER, true)

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so one with no strong
    // reference is collected and silently stops firing.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_HAND -> handCache = p.getBoolean(KEY_HAND, true)
            KEY_FACE -> faceCache = p.getBoolean(KEY_FACE, true)
            KEY_BUBBLE -> bubbleCache = p.getBoolean(KEY_BUBBLE, true)
            KEY_PAUSE_SCREEN_OFF -> pauseScreenOffCache = p.getBoolean(KEY_PAUSE_SCREEN_OFF, true)
            KEY_WAVE -> waveCache = p.getBoolean(KEY_WAVE, true)
            KEY_POINTER -> pointerCache = p.getBoolean(KEY_POINTER, true)
            // key is null when preferences are cleared wholesale; re-read everything.
            null -> {
                handCache = p.getBoolean(KEY_HAND, true)
                faceCache = p.getBoolean(KEY_FACE, true)
                bubbleCache = p.getBoolean(KEY_BUBBLE, true)
                pauseScreenOffCache = p.getBoolean(KEY_PAUSE_SCREEN_OFF, true)
                waveCache = p.getBoolean(KEY_WAVE, true)
                pointerCache = p.getBoolean(KEY_POINTER, true)
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

    /**
     * Release the camera while the screen is off. On by default: with the screen off the phone
     * is usually pocketed or face down, so the camera films nothing while the detector drains
     * the battery on it — comfortably the largest power saving available here.
     *
     * Turning it off keeps detection running so a gesture can wake the screen, at a real cost in
     * battery life.
     */
    var pauseWhenScreenOff: Boolean
        get() = pauseScreenOffCache
        set(value) {
            pauseScreenOffCache = value
            prefs.edit().putBoolean(KEY_PAUSE_SCREEN_OFF, value).apply()
        }

    /**
     * Wave gestures via the proximity sensor. Kept separate from the camera modalities because it
     * is a different kind of trade: a tiny vocabulary — waves only, no shape or direction — for
     * almost no battery cost, and it keeps working while the screen is off and the camera is
     * released.
     */
    var waveEnabled: Boolean
        get() = waveCache
        set(value) {
            waveCache = value
            prefs.edit().putBoolean(KEY_WAVE, value).apply()
        }

    /**
     * Air pointer: a dot on screen follows the pointed finger, and holding it still puts a pen
     * down so the next movement drags — a scroll, a swipe, or a line of whatever shape the finger
     * traces.
     *
     * On by default, which it was not when the alternative was four fixed-length swipes. The
     * pointer is now a superset of that: scrolling and turning are drags, of the length the user
     * actually made, and curves exist at all. It still *replaces* the swipe mode rather than
     * running alongside it, because a dot tracking your finger while the page scrolls under it
     * reads as the pointer causing the scroll.
     *
     * Requires the overlay permission. Without it the swipe mode is used instead — see
     * GestureControlService: a cursor nobody can see, dragging things, is worse than no cursor.
     */
    var pointerEnabled: Boolean
        get() = pointerCache
        set(value) {
            pointerCache = value
            prefs.edit().putBoolean(KEY_POINTER, value).apply()
        }

    private companion object {
        const val KEY_HAND = "hand_enabled"
        const val KEY_FACE = "face_enabled"
        const val KEY_BUBBLE = "bubble_enabled"
        const val KEY_PAUSE_SCREEN_OFF = "pause_when_screen_off"
        const val KEY_WAVE = "wave_enabled"
        const val KEY_POINTER = "pointer_enabled"
    }
}
