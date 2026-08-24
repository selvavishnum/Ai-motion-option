package com.aimotion.handsfree.gesture

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aimotion.handsfree.MainActivity
import com.aimotion.handsfree.R

/**
 * Quick Settings tile that switches air gestures on and off from the notification shade, next to
 * Wi-Fi and Torch — the point of the app being hands-free is undercut if turning it on means
 * finding and opening the app first.
 *
 * The tile is a *view* of [GestureControlService.isRunning], never its own source of truth: the
 * service can also be started from the app or stopped by the system, and a tile showing a state
 * the service isn't in is worse than no tile. Every path here re-reads the flag rather than
 * assuming the click did what it asked.
 */
class GestureTileService : TileService() {

    /** Called when the shade opens with this tile visible, so it can never be shown stale. */
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // Nothing can run without the camera and the accessibility service, and neither can be
        // granted from a tile — both are OS permission screens. Send the user to the app rather
        // than starting a service that would sit there doing nothing.
        if (!ServicePrerequisites.areMet(this)) {
            openApp()
            return
        }
        if (GestureControlService.isRunning) {
            GestureControlService.stop(this)
        } else {
            GestureControlService.start(this)
        }
        // The service updates the tile itself once it has actually started or stopped, but that
        // is a service lifecycle callback and lands a moment later; repaint now so the tile
        // doesn't visibly lag the tap.
        render()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 removed the Intent overload: a tile may only launch an activity through
            // a PendingIntent it owns, so a malicious tile can't start arbitrary components.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        // qsTile is null outside the listening window (the shade is closed, or the tile was
        // removed while a callback was in flight).
        val tile = qsTile ?: return
        val ready = ServicePrerequisites.areMet(this)
        val running = GestureControlService.isRunning

        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        // A subtitle only exists from Android 10 on; the label carries the state on older builds,
        // but minSdk here is 31 so this is simply the supported API.
        tile.subtitle = when {
            !ready -> getString(R.string.tile_subtitle_setup)
            running -> getString(R.string.tile_subtitle_on)
            else -> getString(R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }

    companion object {
        /**
         * Asks the system to call [onStartListening] so the tile repaints. Safe to call when the
         * tile isn't on the user's shade — the platform ignores it — and cheap enough to call on
         * every service start and stop.
         */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, GestureTileService::class.java),
                )
            }
        }
    }
}
