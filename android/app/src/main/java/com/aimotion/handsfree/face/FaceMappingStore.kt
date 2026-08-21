package com.aimotion.handsfree.face

import android.content.Context
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.GestureAction
import org.json.JSONObject

/** Persists the user's face-gesture -> action mapping, mirroring GestureMappingStore for hand
 * gestures but in its own SharedPreferences file/key so the two never collide. */
class FaceMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("face_mapping", Context.MODE_PRIVATE)

    fun load(): Map<FaceGesture, GestureAction> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULT_FACE_MAPPING
        return try {
            val json = JSONObject(raw)
            FaceGesture.entries.associateWith { gesture ->
                val entry = json.optJSONObject(gesture.name)
                    ?: return@associateWith DEFAULT_FACE_MAPPING.getValue(gesture)
                GestureAction(
                    type = ActionType.valueOf(entry.getString("type")),
                    packageName = entry.optString("packageName").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            DEFAULT_FACE_MAPPING
        }
    }

    fun save(mapping: Map<FaceGesture, GestureAction>) {
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
