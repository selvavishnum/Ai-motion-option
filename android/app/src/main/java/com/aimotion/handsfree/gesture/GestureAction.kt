package com.aimotion.handsfree.gesture

enum class ActionType { SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TAP, BACK, HOME, LAUNCH_APP }

data class GestureAction(val type: ActionType, val packageName: String? = null) {
    fun describe(): String = when (type) {
        ActionType.LAUNCH_APP -> "Launch app" + (packageName?.let { " ($it)" } ?: " (none chosen)")
        else -> type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Default preset: air-gestures cover the most common hands-free needs out of the box —
 * scrolling reels/shorts/articles (swipe), selecting (tap), and navigating (back/home). Any
 * entry can be remapped in Settings, including to "launch app" for a chosen app. */
val DEFAULT_MAPPING: Map<Gesture, GestureAction> = mapOf(
    Gesture.OPEN_PALM to GestureAction(ActionType.BACK),
    Gesture.FIST to GestureAction(ActionType.HOME),
    Gesture.POINT to GestureAction(ActionType.TAP),
    Gesture.PEACE to GestureAction(ActionType.SWIPE_RIGHT),
    Gesture.THUMBS_UP to GestureAction(ActionType.SWIPE_UP),
    Gesture.THUMBS_DOWN to GestureAction(ActionType.SWIPE_DOWN),
)
