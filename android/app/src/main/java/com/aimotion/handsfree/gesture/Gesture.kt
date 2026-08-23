package com.aimotion.handsfree.gesture

/**
 * Port of the Python reference implementation (app/gesture.py in the repo root) so both the
 * server-side API and this on-device classifier agree on what each landmark shape means.
 * Landmarks follow the MediaPipe Hands topology: 21 points, normalized image coordinates
 * where y increases downward.
 */

data class Point(val x: Float, val y: Float, val z: Float = 0f)

enum class Handedness { LEFT, RIGHT }

enum class Gesture(val label: String) {
    FIST("fist"),
    OPEN_PALM("open_palm"),
    THUMBS_UP("thumbs_up"),
    THUMBS_DOWN("thumbs_down"),
    POINT("point"),
    PEACE("peace"),
    UNKNOWN("unknown"),
}

private const val WRIST = 0
private const val THUMB_IP = 3
private const val THUMB_TIP = 4
private val FINGER_TIP_PIP = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18) // index, middle, ring, pinky
private const val NUM_LANDMARKS = 21

private fun fingerExtended(landmarks: List<Point>, tip: Int, pip: Int): Boolean =
    landmarks[tip].y < landmarks[pip].y

private fun thumbExtended(landmarks: List<Point>, handedness: Handedness): Boolean {
    val tipX = landmarks[THUMB_TIP].x
    val ipX = landmarks[THUMB_IP].x
    return if (handedness == Handedness.RIGHT) tipX < ipX else tipX > ipX
}

fun extendedFingers(landmarks: List<Point>, handedness: Handedness): List<Boolean> {
    require(landmarks.size == NUM_LANDMARKS) { "expected $NUM_LANDMARKS landmarks, got ${landmarks.size}" }
    return listOf(thumbExtended(landmarks, handedness)) +
        FINGER_TIP_PIP.map { (tip, pip) -> fingerExtended(landmarks, tip, pip) }
}

/**
 * Runs on every camera frame, so it reads the five finger states directly rather than going
 * through [extendedFingers]. That path allocated three lists per call — `listOf(thumb)`, the
 * `.map` over the tip/pip pairs, and the `+` that joined them — plus a wrapper object for the
 * destructuring, to answer five boolean questions. Same logic, same results, no garbage.
 */
fun classifyGesture(landmarks: List<Point>, handedness: Handedness): Gesture {
    require(landmarks.size == NUM_LANDMARKS) { "expected $NUM_LANDMARKS landmarks, got ${landmarks.size}" }
    val thumb = thumbExtended(landmarks, handedness)
    val index = fingerExtended(landmarks, 8, 6)
    val middle = fingerExtended(landmarks, 12, 10)
    val ring = fingerExtended(landmarks, 16, 14)
    val pinky = fingerExtended(landmarks, 20, 18)
    val othersUp = index && middle && ring && pinky
    val othersDown = !(index || middle || ring || pinky)

    return when {
        othersUp && thumb -> Gesture.OPEN_PALM
        othersDown && !thumb -> Gesture.FIST
        othersDown && thumb -> {
            val wristY = landmarks[WRIST].y
            val tipY = landmarks[THUMB_TIP].y
            if (tipY < wristY) Gesture.THUMBS_UP else Gesture.THUMBS_DOWN
        }
        index && middle && !ring && !pinky -> Gesture.PEACE
        index && !middle && !ring && !pinky -> Gesture.POINT
        else -> Gesture.UNKNOWN
    }
}
