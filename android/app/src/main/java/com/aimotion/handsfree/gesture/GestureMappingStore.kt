package com.aimotion.handsfree.gesture

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Gestures that appear in the remappable mapping table/UI. [Gesture.UNKNOWN] is a
 * classification fallback, not a real trigger; [Gesture.POINT] drives the continuous
 * finger-trackpad instead of a single fixed action (see GestureControlService), so neither has
 * an entry in [DEFAULT_MAPPING] and both are excluded here. */
val MAPPABLE_GESTURES: List<Gesture> = Gesture.entries.filter {
    it != Gesture.UNKNOWN && it != Gesture.POINT && it != Gesture.PEACE
}

/** Persists the user's gesture → action mapping as a small JSON blob in SharedPreferences. */
class GestureMappingStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_mapping", Context.MODE_PRIVATE)

    // load() is called on every gesture that fires, from the detection callback thread, and used
    // to re-parse the JSON and rebuild the map each time. The parsed result is cached and
    // dropped whenever the file changes, so edits in Settings still take effect immediately
    // while the hot path costs a single volatile read.
    @Volatile
    private var cached: Map<Gesture, GestureAction>? = null

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so a listener with no
    // strong reference is collected and silently stops firing.
    private val invalidate = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cached = null
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(invalidate)
    }

    fun load(): Map<Gesture, GestureAction> = cached ?: parse().also { cached = it }

    private fun parse(): Map<Gesture, GestureAction> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULT_MAPPING
        return try {
            val json = JSONObject(raw)
            MAPPABLE_GESTURES.associateWith { gesture ->
                val entry = json.optJSONObject(gesture.name)
                    ?: return@associateWith DEFAULT_MAPPING.getValue(gesture)
                // An action type this build no longer has (LOCK_SCREEN, removed with device
                // admin) falls back to the default for that trigger alone, rather than throwing
                // and taking every other saved mapping down with it.
                val type = actionTypeOrNull(entry.getString("type"))
                    ?: return@associateWith DEFAULT_MAPPING.getValue(gesture)
                GestureAction(
                    type = type,
                    packageName = entry.optString("packageName").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            DEFAULT_MAPPING
        }
    }

    fun save(mapping: Map<Gesture, GestureAction>) {
        // Also invalidated directly rather than relying only on the listener: apply() writes
        // asynchronously and the callback lands on the main thread later, so a read in between
        // would otherwise serve the stale mapping.
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
