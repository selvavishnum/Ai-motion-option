package com.aimotion.handsfree.gesture

import android.content.Context
import org.json.JSONObject

/** Persists the user's gesture → action mapping as a small JSON blob in SharedPreferences. */
class GestureMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_mapping", Context.MODE_PRIVATE)

    fun load(): Map<Gesture, GestureAction> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULT_MAPPING
        return try {
            val json = JSONObject(raw)
            Gesture.entries.filter { it != Gesture.UNKNOWN }.associateWith { gesture ->
                val entry = json.optJSONObject(gesture.name)
                    ?: return@associateWith DEFAULT_MAPPING.getValue(gesture)
                GestureAction(
                    type = ActionType.valueOf(entry.getString("type")),
                    packageName = entry.optString("packageName").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            DEFAULT_MAPPING
        }
    }

    fun save(mapping: Map<Gesture, GestureAction>) {
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
