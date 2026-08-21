package com.aimotion.handsfree.gesture

import java.lang.ref.WeakReference

/** Bridges the camera/gesture-detection service to the AccessibilityService that actually
 * performs system actions — they're separate Android components that can't call each other
 * directly, so the AccessibilityService registers itself here once connected. */
object ActionDispatcher {
    private var serviceRef: WeakReference<GestureAccessibilityService>? = null

    fun attach(service: GestureAccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun detach(service: GestureAccessibilityService) {
        if (serviceRef?.get() === service) serviceRef = null
    }

    val isReady: Boolean get() = serviceRef?.get() != null

    fun fire(action: GestureAction) {
        serviceRef?.get()?.perform(action)
    }

    fun pinch(zoomIn: Boolean) {
        serviceRef?.get()?.pinch(zoomIn)
    }
}
