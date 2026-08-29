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

/**
 * Default preset.
 *
 * Two gestures are deliberately absent, because between them they *are* the pointer and giving
 * either one a second job would make the pointer unusable:
 *
 * - [Gesture.POINT] — a single index finger moves the cursor and, held still, puts the pen down
 *   to drag or draw. See AirPointer.
 * - [Gesture.PEACE] — the click. A pointer with no button can only ever select by dwelling,
 *   which forces every deliberate pause to become a tap; a separate pose for "select" is what
 *   lets the cursor be parked somewhere without something happening.
 *
 * Neither appears in the remappable table, so neither can be reassigned out from under the
 * pointer.
 */
val DEFAULT_MAPPING: Map<Gesture, GestureAction> = mapOf(
    Gesture.OPEN_PALM to GestureAction(ActionType.WAKE_SCREEN),
    Gesture.FIST to GestureAction(ActionType.HOME),
    Gesture.THUMBS_UP to GestureAction(ActionType.SWIPE_UP),
    Gesture.THUMBS_DOWN to GestureAction(ActionType.SWIPE_DOWN),
)
