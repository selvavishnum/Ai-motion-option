package com.aimotion.handsfree.gesture

enum class ActionType {
    SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TAP, BACK, HOME, LAUNCH_APP,

    /** Opens the Recents/app-switcher overview — the real, reliable version of "next app".
     * Android exposes no API for silently jumping to a specific other app; only the switcher
     * UI is available, same as swiping up-and-hold on stock Android gesture navigation. */
    RECENTS,

    /** Briefly wakes the display (a real, legitimate wake-lock, same mechanism alarm apps use).
     * There is no counterpart action that turns the screen off: Android has no API for a
     * regular app to do that — only a Device Admin app can lock the screen, and that requires
     * a much heavier "Activate device administrator" permission most users would decline. */
    WAKE_SCREEN,
}

data class GestureAction(val type: ActionType, val packageName: String? = null) {
    fun describe(): String = when (type) {
        ActionType.LAUNCH_APP -> "Launch app" + (packageName?.let { " ($it)" } ?: " (none chosen)")
        else -> type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Default preset. [Gesture.POINT] is deliberately absent: holding up a single index finger
 * now drives a continuous mini-trackpad (turn = swipe left/right, scroll = swipe up/down, hold
 * still = tap/select) rather than firing one fixed action, so it never appears in the
 * remappable table — see GestureControlService's finger-trackpad tracking. */
val DEFAULT_MAPPING: Map<Gesture, GestureAction> = mapOf(
    Gesture.OPEN_PALM to GestureAction(ActionType.WAKE_SCREEN),
    Gesture.FIST to GestureAction(ActionType.HOME),
    Gesture.PEACE to GestureAction(ActionType.SWIPE_RIGHT),
    Gesture.THUMBS_UP to GestureAction(ActionType.SWIPE_UP),
    Gesture.THUMBS_DOWN to GestureAction(ActionType.SWIPE_DOWN),
)
