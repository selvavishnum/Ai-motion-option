package com.aimotion.handsfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.aimotion.handsfree.databinding.ActivityMainBinding
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.Gesture
import com.aimotion.handsfree.gesture.GestureAction
import com.aimotion.handsfree.gesture.GestureControlService
import com.aimotion.handsfree.gesture.GestureMappingStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mappingStore: GestureMappingStore
    private lateinit var mapping: MutableMap<Gesture, GestureAction>

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

        binding.mappingList.layoutManager = LinearLayoutManager(this)
        binding.mappingList.adapter = GestureMappingAdapter(
            gestures = Gesture.entries.filter { it != Gesture.UNKNOWN },
            mapping = mapping,
            onChanged = { _, _ -> mappingStore.save(mapping) },
            onChooseApp = ::showAppPicker,
        )

        binding.grantCameraButton.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.serviceSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) maybeStartService() else GestureControlService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        binding.statusText.text = buildString {
            append(if (cameraOk) "Camera: granted" else "Camera: not granted")
            append(" · ")
            append(if (a11yOk) "Accessibility: on" else "Accessibility: off")
        }
        binding.grantCameraButton.isEnabled = !cameraOk
        binding.serviceSwitch.isEnabled = cameraOk && a11yOk
    }

    private fun maybeStartService() {
        if (hasCameraPermission() && isAccessibilityServiceEnabled()) {
            GestureControlService.start(this)
        }
    }

    private fun showAppPicker(gesture: Gesture) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Choose app for ${gesture.label}")
            .setItems(labels) { _, index ->
                val packageName = apps[index].activityInfo.packageName
                val newAction = GestureAction(ActionType.LAUNCH_APP, packageName)
                mapping[gesture] = newAction
                mappingStore.save(mapping)
                binding.mappingList.adapter?.notifyDataSetChanged()
            }
            .show()
    }
}
