package com.aimotion.handsfree.gesture

enum class ActionType {
    SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TAP, BACK, HOME, LAUNCH_APP,

    /** Opens the Recents/app-switcher overview — the real, reliable version of "next app".
     * Android exposes no API for silently jumping to a specific other app; only the switcher
     * UI is available, same as swiping up-and-hold on stock Android gesture navigation. */
    RECENTS,

    /** Briefly wakes the display (a real, legitimate wake-lock, same mechanism alarm apps use). */
    WAKE_SCREEN,
}

/**
 * Resolves a stored action-type name, returning null for one this build no longer has.
 *
 * [ActionType.valueOf] throws on an unknown name, which matters because saved mappings outlive
 * the enum: LOCK_SCREEN was removed when device admin was dropped, so an upgrading user has
 * "LOCK_SCREEN" written in their preferences. Without this, every store's parse would throw and
 * discard the user's *entire* mapping rather than the one entry that no longer resolves.
 */
fun actionTypeOrNull(name: String): ActionType? = ActionType.entries.firstOrNull { it.name == name }

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
