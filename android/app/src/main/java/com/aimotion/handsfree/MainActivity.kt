package com.aimotion.handsfree

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.StatusBarManager
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aimotion.handsfree.databinding.ActivityMainBinding
import com.aimotion.handsfree.face.FaceGesture
import com.aimotion.handsfree.face.FaceMappingStore
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.Gesture
import com.aimotion.handsfree.gesture.GestureAction
import com.aimotion.handsfree.gesture.GestureControlService
import com.aimotion.handsfree.gesture.GestureMappingStore
import com.aimotion.handsfree.gesture.GestureTileService
import com.aimotion.handsfree.gesture.GestureToggleStore
import com.aimotion.handsfree.gesture.AirSensorDeviceAdminReceiver
import com.aimotion.handsfree.gesture.MAPPABLE_GESTURES
import com.aimotion.handsfree.gesture.ProximityGesture
import com.aimotion.handsfree.gesture.ProximityMappingStore
import com.aimotion.handsfree.gesture.ServicePrerequisites
import com.aimotion.handsfree.overlay.OverlayBubbleService
import com.aimotion.handsfree.ui.paper.PaperShowcaseActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mappingStore: GestureMappingStore
    private lateinit var mapping: MutableMap<Gesture, GestureAction>
    private lateinit var faceMappingStore: FaceMappingStore
    private lateinit var faceMapping: MutableMap<FaceGesture, GestureAction>
    private lateinit var toggles: GestureToggleStore
    private lateinit var waveMappingStore: ProximityMappingStore
    private lateinit var waveMapping: MutableMap<ProximityGesture, GestureAction>

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

        mappingStore = GestureMappingStore(this)
        mapping = mappingStore.load().toMutableMap()
        faceMappingStore = FaceMappingStore(this)
        faceMapping = faceMappingStore.load().toMutableMap()
        toggles = GestureToggleStore(this)
        waveMappingStore = ProximityMappingStore(this)
        waveMapping = waveMappingStore.load().toMutableMap()

        binding.mappingList.layoutManager = LinearLayoutManager(this)
        binding.mappingList.adapter = GestureMappingAdapter(
            triggers = MAPPABLE_GESTURES,
            labelOf = { it.label },
            mapping = mapping,
            onChanged = { _, _ -> mappingStore.save(mapping) },
            onChooseApp = { gesture ->
                showAppPicker(gesture.label) { packageName ->
                    mapping[gesture] = GestureAction(ActionType.LAUNCH_APP, packageName)
                    mappingStore.save(mapping)
                    binding.mappingList.adapter?.notifyDataSetChanged()
                }
            },
        )

        binding.faceMappingList.layoutManager = LinearLayoutManager(this)
        binding.faceMappingList.adapter = GestureMappingAdapter(
            triggers = FaceGesture.entries.toList(),
            labelOf = { it.label },
            mapping = faceMapping,
            onChanged = { _, _ -> faceMappingStore.save(faceMapping) },
            onChooseApp = { gesture ->
                showAppPicker(gesture.label) { packageName ->
                    faceMapping[gesture] = GestureAction(ActionType.LAUNCH_APP, packageName)
                    faceMappingStore.save(faceMapping)
                    binding.faceMappingList.adapter?.notifyDataSetChanged()
                }
            },
        )

        // The adapter is generic over its trigger type, so a third modality reuses it rather than
        // duplicating a near-identical list.
        binding.waveMappingList.layoutManager = LinearLayoutManager(this)
        binding.waveMappingList.adapter = GestureMappingAdapter(
            triggers = ProximityGesture.entries.toList(),
            labelOf = { it.label },
            mapping = waveMapping,
            onChanged = { _, _ -> waveMappingStore.save(waveMapping) },
            onChooseApp = { gesture ->
                showAppPicker(gesture.label) { packageName ->
                    waveMapping[gesture] = GestureAction(ActionType.LAUNCH_APP, packageName)
                    waveMappingStore.save(waveMapping)
                    binding.waveMappingList.adapter?.notifyDataSetChanged()
                }
            },
        )

        binding.gestureGuideButton.setOnClickListener {
            startActivity(Intent(this, GestureGuideActivity::class.java))
        }
        binding.gestureMappingComposeButton.setOnClickListener {
            startActivity(Intent(this, GestureMappingComposeActivity::class.java))
        }
        binding.sensorListButton.setOnClickListener {
            startActivity(Intent(this, SensorListActivity::class.java))
        }
        binding.paperShowcaseButton.setOnClickListener {
            startActivity(Intent(this, PaperShowcaseActivity::class.java))
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
        binding.openBatteryButton.setOnClickListener {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        binding.addTileButton.setOnClickListener { requestAddQuickSettingsTile() }
        binding.restrictedSettingsButton.setOnClickListener { showRestrictedSettingsHelp() }
        binding.deviceAdminButton.setOnClickListener { requestDeviceAdmin() }

        binding.handGestureSwitch.isChecked = toggles.handEnabled
        binding.faceGestureSwitch.isChecked = toggles.faceEnabled
        binding.handGestureSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.handEnabled = checked
            refreshStatus()
        }
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
        binding.bubbleSwitch.isChecked = toggles.bubbleEnabled
        binding.bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            toggles.bubbleEnabled = checked
            if (checked) OverlayBubbleService.start(this) else OverlayBubbleService.stop(this)
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

    // Both questions moved to ServicePrerequisites so the Quick Settings tile asks them the same
    // way; a second copy of the accessibility-list parsing was exactly the kind of thing that
    // drifts.
    private fun hasCameraPermission() = ServicePrerequisites.hasCameraPermission(this)

    private fun isAccessibilityServiceEnabled() = ServicePrerequisites.isAccessibilityServiceEnabled(this)

    private fun deviceAdminComponent() = ComponentName(this, AirSensorDeviceAdminReceiver::class.java)

    private fun isDeviceAdminActive(): Boolean =
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(deviceAdminComponent())

    /** The only way an ordinary app can turn the screen off is as a force-lock device admin, so
     * the "screen off" gesture needs this separate grant. The explanation is spelled out because
     * the system dialog that follows is deliberately alarming, and the user deserves to know it
     * is being asked for exactly one capability. */
    private fun requestDeviceAdmin() {
        if (isDeviceAdminActive()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Screen off is already allowed")
                .setMessage(
                    "Air Sensor is an active device admin, so the \"screen off\" gesture works.\n\n" +
                        "To undo it: Settings → Security → Device admin apps → Air Sensor → Deactivate. " +
                        "Everything else keeps working without it."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent())
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets the closed-palm gesture turn the screen off. This is the only device-admin " +
                    "power Air Sensor asks for — it cannot wipe your data or set passwords.",
            )
        startActivity(intent)
    }

    private fun refreshStatus() {
        val cameraOk = hasCameraPermission()
        val a11yOk = isAccessibilityServiceEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        val adminOk = isDeviceAdminActive()

        binding.statusText.text = buildString {
            append(if (cameraOk) "Camera: granted" else "Camera: not granted")
            append(" · ")
            append(if (a11yOk) "Accessibility: on" else "Accessibility: off")
            append(" · ")
            append(if (overlayOk) "Overlay: on" else "Overlay: off")
            append(" · ")
            append(if (batteryOk) "Battery: unrestricted" else "Battery: restricted")
            append(" · ")
            append(if (adminOk) "Screen off: allowed" else "Screen off: not allowed")
        }
        binding.grantCameraButton.isEnabled = !cameraOk
        // Was missing, unlike every other permission button here: the accessibility button stayed
        // lit and enabled even once the service was on, which reads as "still not granted" no
        // matter what the status line says.
        binding.openAccessibilityButton.isEnabled = !a11yOk
        binding.openAccessibilityButton.text =
            if (a11yOk) "Accessibility permission granted ✓" else "Turn on Accessibility permission"
        binding.openOverlayButton.isEnabled = !overlayOk
        binding.openBatteryButton.isEnabled = !batteryOk
        binding.serviceSwitch.isEnabled = cameraOk && a11yOk
        // Reflect what the service is actually doing, not what this screen last asked for: it can
        // now also be toggled from the Quick Settings tile, and it could have been stopped by the
        // system while the app was in the background.
        binding.serviceSwitch.setOnCheckedChangeListener(null)
        binding.serviceSwitch.isChecked = GestureControlService.isRunning
        binding.serviceSwitch.setOnCheckedChangeListener(serviceSwitchListener)
        binding.bubbleSwitch.isEnabled = overlayOk
        // The pointer draws through the same overlay window as the bubble, so without that
        // permission the switch would turn on and produce nothing visible.
        binding.pointerSwitch.isEnabled = overlayOk
        if (!overlayOk) {
            binding.pointerHintText.text =
                "Needs the floating bubble permission above before the pointer can be drawn."
        }

        // Not every phone has a proximity sensor. Say so plainly rather than leaving a switch
        // that silently does nothing.
        val hasProximity = (getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        binding.waveGestureSwitch.isEnabled = hasProximity
        binding.waveHintText.text = if (hasProximity) {
            "Uses the proximity sensor instead of the camera — almost no battery, and it still works while the screen is off. Only wave once or twice; it can't see hand shapes."
        } else {
            "This phone has no proximity sensor, so wave gestures aren't available here."
        }

        // Spell out the live speed consequence of the two switches, since it isn't obvious that
        // turning one modality off makes the other one react faster.
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
                    "Pull down the notification shade, tap the pencil or ✎ / edit button, then " +
                        "drag the \"Air gestures\" tile up into the active row."
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
            // The system already shows its own dialog and its own confirmation; there is nothing
            // useful to add on top of it, whichever way the user answered.
        }
    }

    private fun maybeStartService() {
        if (hasCameraPermission() && isAccessibilityServiceEnabled()) {
            GestureControlService.start(this)
        }
    }

    /** Android 13+ puts Accessibility and "display over other apps" behind "Restricted settings"
     * for any app installed outside an app store — that's the "App was denied access" dialog.
     * It is an OS security gate and no app can lift it for itself, by design. What this app
     * *can* do is stop sending people to a dead end: the block appears on the Accessibility and
     * overlay screens, but the unlock lives in this app's own App info page, so point there and
     * spell out the taps. */
    private fun showRestrictedSettingsHelp() {
        val steps = """
            Android blocks these permissions for apps installed outside an app store. Air Sensor cannot grant them to itself — you unlock it once, by hand:

            1. Tap "Open app info" below.
            2. Tap ⋮ (three dots, top right).
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
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAppPicker(triggerLabel: String, onPicked: (String) -> Unit) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Choose app for $triggerLabel")
            .setItems(labels) { _, index -> onPicked(apps[index].activityInfo.packageName) }
            .show()
    }
}
