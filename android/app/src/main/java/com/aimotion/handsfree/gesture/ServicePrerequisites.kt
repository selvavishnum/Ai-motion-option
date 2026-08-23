package com.aimotion.handsfree.gesture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat

/**
 * The two things gesture control cannot run without: the camera, and the accessibility service
 * that actually performs the gestures in other apps.
 *
 * Lives here rather than in MainActivity because the Quick Settings tile asks the same questions
 * from outside any activity, and a tile that starts the service while accessibility is off would
 * leave a foreground notification running that can never do anything.
 */
object ServicePrerequisites {

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${GestureAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // The list is colon-separated; SimpleStringSplitter avoids allocating the split array.
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next() == expected) return true
        }
        return false
    }

    /** Whether [GestureControlService] can be started right now and do useful work. */
    fun areMet(context: Context): Boolean =
        hasCameraPermission(context) && isAccessibilityServiceEnabled(context)
}
