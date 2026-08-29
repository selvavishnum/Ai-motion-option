package com.aimotion.handsfree.gesture

import android.content.Context
import android.content.SharedPreferences

/**
 * How readily a gesture fires: one user-facing dial from 1 (least sensitive) to 5 (most).
 *
 * It is one dial rather than the six separate numbers it actually drives, because the complaint
 * behind it is always one of two things — "it triggers when I didn't mean to" or "I have to move
 * miles before it notices" — and those sit at opposite ends of a single axis. Splitting movement
 * distance and pose confirmation into two controls would ask the user to diagnose which half is
 * wrong, which is exactly the thing they came here unable to do.
 *
 * The two move together for a reason: a lower movement threshold means noisier input reaching the
 * classifier, which is when *more* frames of confirmation are worth paying for, and vice versa.
 *
 * Values are mirrored in a volatile field because the detection loop reads them on every frame.
 */
class SensitivityStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_sensitivity", Context.MODE_PRIVATE)

    @Volatile private var levelCache = prefs.getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so one with no strong
    // reference is collected and silently stops firing.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == KEY_LEVEL || key == null) {
            levelCache = p.getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    var level: Int
        get() = levelCache
        set(value) {
            val clamped = value.coerceIn(MIN_LEVEL, MAX_LEVEL)
            // Written through immediately as well as via the listener: apply() is asynchronous and
            // its callback lands later, so a read in between would serve the old value.
            levelCache = clamped
            prefs.edit().putInt(KEY_LEVEL, clamped).apply()
        }

    companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 5
        const val DEFAULT_LEVEL = 3

        /**
         * Multiplier applied to every movement threshold. Level 3 is 1.0 — the values the rest of
         * the app was tuned around — so the default behaviour is exactly what it was before this
         * setting existed.
         *
         * The steps are uneven on purpose. Going *less* sensitive only needs to ask for a bigger,
         * more deliberate movement, so those steps are gentle; going *more* sensitive quickly
         * approaches the landmark jitter floor, where the classifier starts reacting to noise
         * rather than to the hand, so the last step down is the largest and 0.55 is as far as it
         * goes.
         */
        fun motionScaleFor(level: Int): Float = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            1 -> 1.60f
            2 -> 1.30f
            3 -> 1.00f
            4 -> 0.75f
            else -> 0.55f
        }

        /**
         * Frames the same pose must be seen for before it fires. More frames costs latency and
         * buys immunity to a hand passing through a pose mid-motion; at the sensitive end two is
         * the floor, since one frame is a single model inference and would misfire constantly.
         */
        fun stableFramesFor(level: Int): Int = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            1 -> 5
            2 -> 4
            3 -> 3
            else -> 2
        }

        fun labelFor(level: Int): String = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            1 -> "Least sensitive — big, deliberate movements only"
            2 -> "Less sensitive — fewer accidental triggers"
            3 -> "Balanced (default)"
            4 -> "More sensitive — reacts to smaller movements"
            else -> "Most sensitive — smallest movements, more misfires"
        }

        private const val KEY_LEVEL = "level"
    }
}
