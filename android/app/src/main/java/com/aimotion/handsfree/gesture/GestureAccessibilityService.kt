package com.aimotion.handsfree.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

private const val TAG = "GestureA11yService"
private const val SWIPE_DURATION_MS = 220L
private const val TAP_DURATION_MS = 60L
private const val PINCH_DURATION_MS = 180L
private const val PINCH_SPAN_FRACTION = 0.12f // how far each finger travels, as a fraction of screen width

/** Performs the on-screen effect of a detected air-gesture on whatever app is currently in the
 * foreground: swipe, tap, back, home, or launching another app. This is the only way a
 * third-party app (Instagram, YouTube, Kindle, a browser, ...) can be driven without root —
 * Android has no API for one app to control another beyond simulated input via this service. */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ActionDispatcher.attach(this)
        Log.i(TAG, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ActionDispatcher.detach(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No window content is needed — this service only injects gestures/global actions.
    }

    override fun onInterrupt() {}

    fun perform(action: GestureAction) {
        when (action.type) {
            ActionType.SWIPE_UP -> swipe(fromFractionY = 0.75f, toFractionY = 0.25f, vertical = true)
            ActionType.SWIPE_DOWN -> swipe(fromFractionY = 0.25f, toFractionY = 0.75f, vertical = true)
            ActionType.SWIPE_LEFT -> swipe(fromFractionY = 0.75f, toFractionY = 0.25f, vertical = false)
            ActionType.SWIPE_RIGHT -> swipe(fromFractionY = 0.25f, toFractionY = 0.75f, vertical = false)
            ActionType.TAP -> tap()
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.LAUNCH_APP -> launchApp(action.packageName)
            ActionType.WAKE_SCREEN -> wakeScreen()
        }
    }

    /** Briefly wakes the display — a real, legitimate wake lock (the same mechanism alarm and
     * call apps use), not a hack. There is no matching "turn screen off": Android has no API
     * for a regular app to do that. */
    private fun wakeScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:gesture-wake",
        )
        wakeLock.acquire(3000L) // auto-releases after the timeout — no manual release() needed
    }

    /** Simulates a two-finger pinch centered on screen — one increment per call. Driven
     * directly by continuous thumb/index-distance tracking (see GestureControlService), not
     * through the discrete gesture->action mapping table, since "pinch" is a motion, not a
     * single pose. */
    fun pinch(zoomIn: Boolean) {
        val (width, height) = screenSize()
        val cx = width / 2f
        val cy = height / 2f
        val span = width * PINCH_SPAN_FRACTION

        val (startOffset, endOffset) = if (zoomIn) 0f to span else span to 0f

        val finger1 = Path().apply {
            moveTo(cx - startOffset, cy)
            lineTo(cx - endOffset, cy)
        }
        val finger2 = Path().apply {
            moveTo(cx + startOffset, cy)
            lineTo(cx + endOffset, cy)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(finger1, 0, PINCH_DURATION_MS))
                .addStroke(GestureDescription.StrokeDescription(finger2, 0, PINCH_DURATION_MS))
                .build(),
            null,
            null,
        )
    }

    private fun screenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun swipe(fromFractionY: Float, toFractionY: Float, vertical: Boolean) {
        val (width, height) = screenSize()
        val path = Path()
        if (vertical) {
            val x = width / 2f
            path.moveTo(x, height * fromFractionY)
            path.lineTo(x, height * toFractionY)
        } else {
            val y = height / 2f
            // fromFractionY/toFractionY reused as X fractions for horizontal swipes.
            path.moveTo(width * fromFractionY, y)
            path.lineTo(width * toFractionY, y)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
                .build(),
            null,
            null,
        )
    }

    private fun tap() {
        val (width, height) = screenSize()
        val path = Path().apply { moveTo(width / 2f, height / 2f) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
            null,
            null,
        )
    }

    private fun launchApp(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
