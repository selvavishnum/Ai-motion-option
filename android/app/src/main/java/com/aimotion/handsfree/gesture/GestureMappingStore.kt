package com.aimotion.handsfree.gesture

import android.content.Context
import org.json.JSONObject

/** Gestures that appear in the remappable mapping table/UI. [Gesture.UNKNOWN] is a
 * classification fallback, not a real trigger; [Gesture.POINT] drives the continuous
 * finger-trackpad instead of a single fixed action (see GestureControlService), so neither has
 * an entry in [DEFAULT_MAPPING] and both are excluded here. */
val MAPPABLE_GESTURES: List<Gesture> = Gesture.entries.filter { it != Gesture.UNKNOWN && it != Gesture.POINT }

/** Persists the user's gesture → action mapping as a small JSON blob in SharedPreferences. */
class GestureMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_mapping", Context.MODE_PRIVATE)

    fun load(): Map<Gesture, GestureAction> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULT_MAPPING
        return try {
            val json = JSONObject(raw)
            MAPPABLE_GESTURES.associateWith { gesture ->
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
