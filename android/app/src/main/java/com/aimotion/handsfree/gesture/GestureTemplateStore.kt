package com.aimotion.handsfree.gesture

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hand shapes a user has recorded for themselves, and the matcher that uses them.
 *
 * The rule-based classifier in [classifyGesture] encodes one opinion about what a fist looks
 * like, tuned on one pair of hands. It cannot be right for everyone — finger length, how far
 * someone actually curls, whether they can fully extend a finger at all — and for an
 * accessibility tool that last one is the whole point.
 *
 * So: record a few examples of each gesture as the user actually makes them, and match new
 * frames against those. No training framework and no model file — normalised landmarks compared
 * against the average of the recorded examples. For a handful of samples per gesture that is
 * both the simplest and the most accurate option available; a network fitted to eight examples
 * would mostly be fitting noise.
 *
 * Port of app/gesture_templates.py, where the geometry is unit-tested.
 */
class GestureTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("gesture_templates", Context.MODE_PRIVATE)

    /** Parsed samples plus their precomputed averages. Rebuilt only when the file changes, since
     * the matcher runs on every frame with a hand in it. */
    @Volatile
    private var cached: Templates? = null

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so one with no strong
    // reference is collected and silently stops firing.
    private val invalidate = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cached = null
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(invalidate)
    }

    private class Templates(
        val samples: Map<Gesture, List<FloatArray>>,
        val centroids: Map<Gesture, FloatArray>,
    )

    private fun templates(): Templates = cached ?: parse().also { cached = it }

    private fun parse(): Templates {
        val raw = prefs.getString(KEY, null) ?: return Templates(emptyMap(), emptyMap())
        val samples = mutableMapOf<Gesture, List<FloatArray>>()
        try {
            val json = JSONObject(raw)
            for (gesture in Gesture.entries) {
                val recorded = json.optJSONArray(gesture.name) ?: continue
                val parsed = ArrayList<FloatArray>(recorded.length())
                for (i in 0 until recorded.length()) {
                    val row = recorded.getJSONArray(i)
                    // A row of the wrong length is from an older, incompatible feature layout.
                    // Skip it rather than throwing away every gesture the user recorded.
                    if (row.length() != FEATURE_LENGTH) continue
                    parsed.add(FloatArray(FEATURE_LENGTH) { row.getDouble(it).toFloat() })
                }
                if (parsed.isNotEmpty()) samples[gesture] = parsed
            }
        } catch (_: Exception) {
            return Templates(emptyMap(), emptyMap())
        }

        val centroids = samples
            .filterValues { it.size >= MIN_SAMPLES }
            .mapValues { (_, recorded) ->
                val mean = FloatArray(FEATURE_LENGTH)
                for (sample in recorded) for (i in 0 until FEATURE_LENGTH) mean[i] += sample[i]
                for (i in 0 until FEATURE_LENGTH) mean[i] /= recorded.size
                mean
            }
        return Templates(samples, centroids)
    }

    private fun save(samples: Map<Gesture, List<FloatArray>>) {
        cached = null
        val json = JSONObject()
        for ((gesture, recorded) in samples) {
            val rows = JSONArray()
            for (sample in recorded) {
                val row = JSONArray()
                for (value in sample) row.put(value.toDouble())
                rows.put(row)
            }
            json.put(gesture.name, rows)
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    /** How many usable examples exist for a gesture. Drives the training screen's progress. */
    fun sampleCount(gesture: Gesture): Int = templates().samples[gesture]?.size ?: 0

    /** Gestures with enough examples to be matched against. */
    fun trainedGestures(): Set<Gesture> = templates().centroids.keys

    fun isTrained(gesture: Gesture): Boolean = templates().centroids.containsKey(gesture)

    /** Records one example. Returns false if the frame wasn't a usable hand. */
    fun record(gesture: Gesture, landmarks: List<Point>): Boolean {
        val features = normalizeLandmarks(landmarks) ?: return false
        val samples = templates().samples.toMutableMap()
        val recorded = samples[gesture].orEmpty().toMutableList()
        // Oldest first: re-recording a gesture should converge on how the user makes it *now*,
        // not average in how they held their hand months ago.
        if (recorded.size >= MAX_SAMPLES) recorded.removeAt(0)
        recorded.add(features)
        samples[gesture] = recorded
        save(samples)
        return true
    }

    fun clear(gesture: Gesture) {
        val samples = templates().samples.toMutableMap()
        samples.remove(gesture)
        save(samples)
    }

    fun clearAll() {
        cached = null
        prefs.edit().remove(KEY).apply()
    }

    /**
     * Matches a hand against the user's recorded examples.
     *
     * Returns null — meaning "use the rule-based classifier for this frame" — rather than
     * guessing, whenever the match is not clearly good: nothing trained, a degenerate hand, a
     * shape far from anything recorded (an unmodelled pose, or a hand caught mid-transition), or
     * two gestures matching almost equally well.
     *
     * Refusing to answer is the right behaviour for something that fires real actions on other
     * apps. A wrong Home press costs the user more than a missed one.
     */
    fun classify(landmarks: List<Point>): Gesture? {
        val centroids = templates().centroids
        if (centroids.isEmpty()) return null
        val features = normalizeLandmarks(landmarks) ?: return null

        var best: Gesture? = null
        var bestDistance = Float.MAX_VALUE
        var secondDistance = Float.MAX_VALUE
        for ((gesture, centroid) in centroids) {
            val distance = featureDistance(features, centroid)
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                best = gesture
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }

        if (bestDistance > MAX_MATCH_DISTANCE) return null
        if (secondDistance - bestDistance < MIN_MARGIN) return null
        return best
    }

    companion object {
        /** Below this, an "average" is one or two hand positions and matching against it would be
         * worse than the rules it replaces. */
        const val MIN_SAMPLES = 5

        /** Enough to cover natural variation; beyond it the average stops moving and the samples
         * are just storage. */
        const val MAX_SAMPLES = 12

        // In normalised-hand units, where 1.0 is the wrist-to-middle-knuckle distance, so they
        // mean the same thing on every hand and at every distance from the camera. Starting
        // points derived from the geometry, not measurements — they want checking against
        // recordings from a real hand, which is why they are named rather than buried inline.
        private const val MAX_MATCH_DISTANCE = 1.6f
        private const val MIN_MARGIN = 0.35f

        private const val KEY = "templates_json"
    }
}
