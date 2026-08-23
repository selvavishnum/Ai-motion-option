package com.aimotion.handsfree.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.aimotion.handsfree.R

private const val TAG = "PointerOverlay"
private const val POINTER_SIZE_PX = 44

/**
 * A dot drawn over every app showing where the pointed finger currently is.
 *
 * Deliberately a plain class rather than a Service, unlike [OverlayBubbleService]. The bubble
 * moves when a person drags it; this moves on every camera frame, and routing that through
 * service Intents would be pure overhead. The owning service already has a Context, so it owns
 * the window directly.
 *
 * **Never touchable.** The window sets FLAG_NOT_TOUCHABLE, which matters twice over: the dot must
 * not swallow the user's real taps, and it must not intercept the app's own synthetic tap — a
 * pointer that blocked the thing it is pointing at would be worse than no pointer.
 */
class PointerOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** Last position in screen pixels, for tapping exactly where the dot is drawn. */
    @Volatile
    var lastX: Int = 0
        private set

    @Volatile
    var lastY: Int = 0
        private set

    @Volatile
    var isShowing: Boolean = false
        private set

    /** Moves the dot, adding it to the window first if needed. Safe to call from any thread. */
    fun moveTo(xPx: Int, yPx: Int) {
        lastX = xPx
        lastY = yPx
        handler.post {
            if (!Settings.canDrawOverlays(context)) return@post
            val wm = windowManager ?: attach() ?: return@post
            val lp = params ?: return@post
            val v = view ?: return@post
            // Centre the dot on the fingertip rather than hanging it off the corner.
            lp.x = xPx - POINTER_SIZE_PX / 2
            lp.y = yPx - POINTER_SIZE_PX / 2
            runCatching { wm.updateViewLayout(v, lp) }
                .onFailure { Log.w(TAG, "failed to move pointer", it) }
            isShowing = true
        }
    }

    fun hide() {
        handler.post {
            val wm = windowManager ?: return@post
            val v = view ?: return@post
            runCatching { wm.removeView(v) }
            windowManager = null
            view = null
            params = null
            isShowing = false
        }
    }

    fun destroy() = hide()

    /** @return the WindowManager once the view is attached, or null if attaching failed. */
    private fun attach(): WindowManager? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            POINTER_SIZE_PX, POINTER_SIZE_PX,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val v = View(context).apply { setBackgroundResource(R.drawable.pointer_dot) }

        return try {
            wm.addView(v, lp)
            windowManager = wm
            view = v
            params = lp
            wm
        } catch (e: Exception) {
            Log.w(TAG, "failed to add pointer overlay", e)
            null
        }
    }
}
