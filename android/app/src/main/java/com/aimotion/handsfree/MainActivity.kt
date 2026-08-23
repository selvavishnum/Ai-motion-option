package com.aimotion.handsfree

import android.Manifest
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aimotion.handsfree.databinding.ActivityMainBinding
import com.aimotion.handsfree.gesture.GestureControlService
import com.aimotion.handsfree.gesture.GestureTileService
import com.aimotion.handsfree.gesture.GestureToggleStore
import com.aimotion.handsfree.gesture.ServicePrerequisites
import com.aimotion.handsfree.overlay.OverlayBubbleService

/**
 * Setup and settings. Deliberately holds no mapping UI of its own any more: hand, face and wave
 * mappings all live on [GestureMappingActivity], which is one screen built from one set of
 * components. This screen used to host three nested RecyclerViews inside a ScrollView, which
 * scrolled badly and buried the switches above them.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var toggles: GestureToggleStore

    /** Held as a field so [refreshStatus] can detach it while writing the switch's state. Setting
     * isChecked fires the listener, which would start or stop the service as a side effect of
     * merely *displaying* what it is already doing. */
    private val serviceSwitchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) maybeStartService() else GestureControlService.stop(this)
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshStatus()
        if (granted) maybeStartService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toggles = GestureToggleStore(this)

        binding.gestureMappingButton.setOnClickListener {
            startActivity(Intent(this, GestureMappingActivity::class.java))
        }
        binding.gestureGuideButton.setOnClickListener {
            startActivity(Intent(this, GestureGuideActivity::class.java))
        }
        binding.grantCameraButton.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.openOverlayButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        binding.openBatteryButton.setOnClickListener { openBatterySettings() }
        binding.addTileButton.setOnClickListener { requestAddQuickSettingsTile() }
        binding.restrictedSettingsButton.setOnClickListener { showRestrictedSettingsHelp() }

        binding.handGestureSwitch.isChecked = toggles.handEnabled
        binding.handGestureSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.handEnabled = checked
            refreshStatus()
        }
        binding.faceGestureSwitch.isChecked = toggles.faceEnabled
        binding.faceGestureSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.faceEnabled = checked
            refreshStatus()
        }
        binding.pointerSwitch.isChecked = toggles.pointerEnabled
        binding.pointerSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.pointerEnabled = checked
            refreshStatus()
        }
        binding.waveGestureSwitch.isChecked = toggles.waveEnabled
        binding.waveGestureSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.waveEnabled = checked
        }
        binding.batterySaverSwitch.isChecked = toggles.pauseWhenScreenOff
        binding.batterySaverSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.pauseWhenScreenOff = checked
        }
        // The floating bubble is a live overlay, so the switch both records the preference and
        // adds or removes the window immediately — a setting that only takes effect next launch
        // would read as broken.
        binding.bubbleSwitch.isChecked = toggles.bubbleEnabled
        binding.bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.bubbleEnabled = checked
            if (checked && Settings.canDrawOverlays(this)) {
                OverlayBubbleService.start(this)
            } else {
                OverlayBubbleService.stop(this)
            }
        }
        binding.serviceSwitch.setOnCheckedChangeListener(serviceSwitchListener)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (Settings.canDrawOverlays(this) && toggles.bubbleEnabled) {
            OverlayBubbleService.start(this)
        } else {
            OverlayBubbleService.stop(this)
        }
    }

    // Both questions live in ServicePrerequisites so the Quick Settings tile asks them the same
    // way; a second copy of the accessibility-list parsing was exactly the kind of thing that
    // drifts.
    private fun hasCameraPermission() = ServicePrerequisites.hasCameraPermission(this)

    private fun isAccessibilityServiceEnabled() = ServicePrerequisites.isAccessibilityServiceEnabled(this)

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    /**
     * Opens the system's battery-optimization list rather than requesting the exemption directly.
     *
     * The direct route needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Play permits only for a
     * short list of app types this one is not on. Reading the current state needs no permission,
     * and neither does opening the list, so the user reaches the same setting — one extra tap to
     * find Air Sensor in it.
     */
    private fun openBatterySettings() {
        // Caught rather than checked with resolveActivity: package-visibility filtering can hide
        // the resolution even where the activity exists, so trying it is the reliable test. Some
        // OEM builds genuinely lack this screen, and App info always reaches battery settings.
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun refreshStatus() {
        val cameraOk = hasCameraPermission()
        val a11yOk = isAccessibilityServiceEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = isIgnoringBatteryOptimizations()

        binding.statusText.text = buildString {
            append(if (cameraOk) "Camera ✓" else "Camera —")
            append("   ")
            append(if (a11yOk) "Accessibility ✓" else "Accessibility —")
            append("   ")
            append(if (overlayOk) "Overlay ✓" else "Overlay —")
            append("   ")
            append(if (batteryOk) "Battery ✓" else "Battery —")
        }

        // Every setup button reports its own state, so a granted permission never leaves a lit
        // button that reads as "still not done".
        binding.grantCameraButton.isEnabled = !cameraOk
        binding.grantCameraButton.text = if (cameraOk) "Camera allowed ✓" else "Allow camera"
        binding.openAccessibilityButton.isEnabled = !a11yOk
        binding.openAccessibilityButton.text =
            if (a11yOk) "Accessibility on ✓" else "Turn on Accessibility permission"
        binding.openOverlayButton.isEnabled = !overlayOk
        binding.openOverlayButton.text =
            if (overlayOk) "Display over other apps allowed ✓" else "Allow display over other apps"
        binding.openBatteryButton.isEnabled = !batteryOk
        binding.openBatteryButton.text =
            if (batteryOk) "Battery unrestricted ✓" else "Stop the system killing Air Sensor"
        binding.batteryHintText.text = if (batteryOk) {
            "Air Sensor is exempt from battery optimization, so gesture control survives in the background."
        } else {
            getString(R.string.battery_hint)
        }

        binding.serviceSwitch.isEnabled = cameraOk && a11yOk
        // Reflect what the service is actually doing, not what this screen last asked for: it can
        // also be toggled from the Quick Settings tile, and the system can stop it while the app
        // is in the background.
        binding.serviceSwitch.setOnCheckedChangeListener(null)
        binding.serviceSwitch.isChecked = GestureControlService.isRunning
        binding.serviceSwitch.setOnCheckedChangeListener(serviceSwitchListener)

        binding.bubbleSwitch.isEnabled = overlayOk
        // The pointer draws through the same overlay window as the bubble, so without that
        // permission the switch would turn on and produce nothing visible.
        binding.pointerSwitch.isEnabled = overlayOk
        if (!overlayOk) {
            val needsPermission = "Needs \"display over other apps\" above."
            binding.bubbleHintText.text = needsPermission
            binding.pointerHintText.text = needsPermission
        } else {
            binding.bubbleHintText.text = getString(R.string.bubble_hint)
            binding.pointerHintText.text = getString(R.string.pointer_hint)
        }

        // Not every phone has a proximity sensor. Say so plainly rather than leaving a switch that
        // silently does nothing.
        val hasProximity = (getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        binding.waveGestureSwitch.isEnabled = hasProximity
        binding.waveHintText.text = if (hasProximity) {
            "Uses the proximity sensor instead of the camera — almost no battery, and it keeps working while the screen is off. Waves only; it cannot see hand shapes."
        } else {
            "This phone has no proximity sensor, so wave gestures aren't available here."
        }

        // Spell out the live speed consequence of the two switches, since it isn't obvious that
        // turning one modality off makes the other react faster.
        binding.speedHintText.text = when {
            toggles.handEnabled && toggles.faceEnabled ->
                "Both on: hand and face take turns on each camera frame. Switch off the one you don't use and the other reacts about twice as fast."
            toggles.handEnabled ->
                "Hand only: every camera frame goes to hand detection — fastest air-gesture response."
            toggles.faceEnabled ->
                "Face only: every camera frame goes to face detection — fastest face-gesture response."
            else ->
                "Both off — nothing will be detected. Turn at least one on."
        }
    }

    /**
     * Android 13+ can ask the user to add a tile with a single dialog. Before that the only route
     * was the shade's own edit screen — findable, but a worse first experience than a button that
     * says what it does. On Android 12 the button explains that route instead of doing nothing.
     */
    private fun requestAddQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Add the tile by hand")
                .setMessage(
                    "Pull down the notification shade, tap the pencil or edit button, then drag " +
                        "the \"Air gestures\" tile up into the active row."
                )
                .setPositiveButton("Got it", null)
                .show()
            return
        }
        val statusBar = getSystemService(StatusBarManager::class.java)
        statusBar.requestAddTileService(
            ComponentName(this, GestureTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile_air_sensor),
            mainExecutor,
        ) {
            // The system shows its own dialog and its own confirmation; there is nothing useful to
            // add on top of it, whichever way the user answered.
        }
    }

    private fun maybeStartService() {
        if (ServicePrerequisites.areMet(this)) GestureControlService.start(this)
    }

    /**
     * Android 13+ puts Accessibility and "display over other apps" behind "Restricted settings"
     * for any app installed outside an app store — that's the "App was denied access" dialog. It
     * is an OS security gate and no app can lift it for itself, by design. What this app *can* do
     * is stop sending people to a dead end: the block appears on the Accessibility and overlay
     * screens, but the unlock lives in this app's own App info page, so point there and spell out
     * the taps. Installing from Play instead makes the whole thing disappear.
     */
    private fun showRestrictedSettingsHelp() {
        val steps = """
            Android blocks these permissions for apps installed outside an app store. Air Sensor cannot grant them to itself — you unlock it once, by hand:

            1. Tap "Open app info" below.
            2. Tap the three dots, top right.
            3. Tap "Allow restricted settings".
            4. Come back here and turn on Accessibility and the floating bubble.

            On Realme / ColorOS you can also reach that page via Settings → Apps → App management → Air Sensor.

            If step 3 is missing from the menu, this phone will only unlock it over ADB from a computer:

            adb shell appops set $packageName ACCESS_RESTRICTED_SETTINGS allow
        """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("\"App was denied access\"")
            .setMessage(steps)
            .setPositiveButton("Open app info") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
