package com.aimotion.handsfree.gesture

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Persists the user's wave-gesture -> action mapping, mirroring GestureMappingStore in its own
 * SharedPreferences file so the three trigger modalities never collide. */
class ProximityMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("proximity_mapping", Context.MODE_PRIVATE)

    // Cached like the other stores: load() is called on every fired gesture, from the sensor
    // callback, and re-parsing JSON there is pure waste.
    @Volatile
    private var cached: Map<ProximityGesture, GestureAction>? = null

    // Strong reference required — SharedPreferences holds listeners weakly.
    private val invalidate = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cached = null
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(invalidate)
    }

    fun load(): Map<ProximityGesture, GestureAction> = cached ?: parse().also { cached = it }

    private fun parse(): Map<ProximityGesture, GestureAction> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULT_PROXIMITY_MAPPING
        return try {
            val json = JSONObject(raw)
            ProximityGesture.entries.associateWith { gesture ->
                val entry = json.optJSONObject(gesture.name)
                    ?: return@associateWith DEFAULT_PROXIMITY_MAPPING.getValue(gesture)
                GestureAction(
                    type = ActionType.valueOf(entry.getString("type")),
                    packageName = entry.optString("packageName").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            DEFAULT_PROXIMITY_MAPPING
        }
    }

    fun save(mapping: Map<ProximityGesture, GestureAction>) {
        cached = null
        val json = JSONObject()
        for ((gesture, action) in mapping) {
            val entry = JSONObject()
            entry.put("type", action.type.name)
            action.packageName?.let { entry.put("packageName", it) }
            json.put(gesture.name, entry)
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    companion object {
        private const val KEY = "mapping_json"
    }
}
