package com.aimotion.handsfree.gesture

import kotlin.math.hypot

/**
 * Port of app/gesture_templates.py's normalisation, kept in step with it by hand — the geometry
 * is tested there, where it can actually be run.
 */

/** The knuckle at the base of the middle finger. Wrist -> this point is the most stable axis on a
 * hand: it barely moves as fingers curl, unlike anything involving the fingertips. */
private const val MIDDLE_MCP = 9

/** 21 points, x and y each. z is dropped — MediaPipe's depth is relative and much noisier than
 * x/y, so including it mostly adds jitter. */
const val FEATURE_LENGTH = NUM_LANDMARKS * 2

/**
 * Reduces a hand to its *shape*, discarding where it is, how big it is, and how it is turned.
 *
 * 1. **Translate** so the wrist is the origin — where the hand is in frame says nothing about
 *    which gesture it is.
 * 2. **Rotate** so the wrist -> middle knuckle axis points straight up. Without this, the same
 *    fist held at a tilt lands far from the recorded fist and the user would have to reproduce
 *    their exact wrist angle every time.
 * 3. **Scale** so that axis has unit length — a hand near the camera and the same hand further
 *    away are the same gesture.
 *
 * Returns null for a degenerate detection (wrist and middle knuckle coincident), which is not a
 * hand; dividing by ~zero there would produce enormous garbage features.
 */
fun normalizeLandmarks(landmarks: List<Point>): FloatArray? {
    if (landmarks.size != NUM_LANDMARKS) return null

    val wrist = landmarks[WRIST]
    val axisX = landmarks[MIDDLE_MCP].x - wrist.x
    val axisY = landmarks[MIDDLE_MCP].y - wrist.y
    val scale = hypot(axisX, axisY)
    if (scale < 1e-6f) return null

    // Rotation taking the axis to (0, -1) — "up", where y grows downward. cos/sin are read off
    // the normalised axis rather than going through atan2 and back.
    val cos = -axisY / scale
    val sin = -axisX / scale

    val features = FloatArray(FEATURE_LENGTH)
    for (i in landmarks.indices) {
        val dx = (landmarks[i].x - wrist.x) / scale
        val dy = (landmarks[i].y - wrist.y) / scale
        features[i * 2] = dx * cos - dy * sin
        features[i * 2 + 1] = dx * sin + dy * cos
    }
    return features
}

/** Euclidean distance between two feature vectors, both of [FEATURE_LENGTH]. */
fun featureDistance(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) {
        val d = a[i] - b[i]
        sum += d * d
    }
    return kotlin.math.sqrt(sum)
}
