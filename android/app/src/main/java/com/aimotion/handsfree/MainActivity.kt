package com.aimotion.handsfree

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.aimotion.handsfree.databinding.ActivityMainBinding
import com.aimotion.handsfree.face.FaceGesture
import com.aimotion.handsfree.face.FaceMappingStore
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.Gesture
import com.aimotion.handsfree.gesture.GestureAction
import com.aimotion.handsfree.gesture.GestureControlService
import com.aimotion.handsfree.gesture.GestureMappingStore
import com.aimotion.handsfree.overlay.OverlayBubbleService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mappingStore: GestureMappingStore
    private lateinit var mapping: MutableMap<Gesture, GestureAction>
    private lateinit var faceMappingStore: FaceMappingStore
    private lateinit var faceMapping: MutableMap<FaceGesture, GestureAction>

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

        binding.mappingList.layoutManager = LinearLayoutManager(this)
        binding.mappingList.adapter = GestureMappingAdapter(
            triggers = Gesture.entries.filter { it != Gesture.UNKNOWN },
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

        binding.gestureGuideButton.setOnClickListener {
            startActivity(Intent(this, GestureGuideActivity::class.java))
        }
        binding.gestureMappingComposeButton.setOnClickListener {
            startActivity(Intent(this, GestureMappingComposeActivity::class.java))
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
        binding.serviceSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) maybeStartService() else GestureControlService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (Settings.canDrawOverlays(this)) OverlayBubbleService.start(this)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/com.aimotion.handsfree.gesture.GestureAccessibilityService"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next() == expected) return true
        }
        return false
    }

    private fun refreshStatus() {
        val cameraOk = hasCameraPermission()
        val a11yOk = isAccessibilityServiceEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        binding.statusText.text = buildString {
            append(if (cameraOk) "Camera: granted" else "Camera: not granted")
            append(" · ")
            append(if (a11yOk) "Accessibility: on" else "Accessibility: off")
            append(" · ")
            append(if (overlayOk) "Overlay: on" else "Overlay: off")
            append(" · ")
            append(if (batteryOk) "Battery: unrestricted" else "Battery: restricted")
        }
        binding.grantCameraButton.isEnabled = !cameraOk
        binding.openOverlayButton.isEnabled = !overlayOk
        binding.openBatteryButton.isEnabled = !batteryOk
        binding.serviceSwitch.isEnabled = cameraOk && a11yOk
    }

    private fun maybeStartService() {
        if (hasCameraPermission() && isAccessibilityServiceEnabled()) {
            GestureControlService.start(this)
        }
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
