package com.aimotion.handsfree.gesture

enum class ActionType {
    SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TAP, BACK, HOME, LAUNCH_APP,

    /** Opens the Recents/app-switcher overview — the real, reliable version of "next app".
     * Android exposes no API for silently jumping to a specific other app; only the switcher
     * UI is available, same as swiping up-and-hold on stock Android gesture navigation. */
    RECENTS,

    /** Briefly wakes the display (a real, legitimate wake-lock, same mechanism alarm apps use). */
    WAKE_SCREEN,

    /** Turns the screen off, via DevicePolicyManager.lockNow(). Android gives an ordinary app no
     * API for this, so it needs the separate "Activate device admin" grant (force-lock only —
     * see AirSensorDeviceAdminReceiver). Without that grant this action does nothing, so the
     * app surfaces a button to turn it on rather than failing silently. */
    LOCK_SCREEN,
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
    Gesture.FIST to GestureAction(ActionType.LOCK_SCREEN),
    Gesture.PEACE to GestureAction(ActionType.SWIPE_RIGHT),
    Gesture.THUMBS_UP to GestureAction(ActionType.SWIPE_UP),
    Gesture.THUMBS_DOWN to GestureAction(ActionType.SWIPE_DOWN),
)
