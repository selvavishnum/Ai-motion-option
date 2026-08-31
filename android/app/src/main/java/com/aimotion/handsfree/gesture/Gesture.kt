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

/** Shared with HandFeatures/GestureTemplateStore, which index the same MediaPipe topology. */
const val WRIST = 0
private const val THUMB_IP = 3
private const val THUMB_TIP = 4
private val FINGER_TIP_PIP = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18) // index, middle, ring, pinky
const val NUM_LANDMARKS = 21

private fun distanceSquared(a: Point, b: Point): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

/**
 * An extended finger reaches further from the wrist than its own middle joint does; a curled one
 * folds the tip back toward the palm, bringing it closer.
 *
 * This used to compare image y ("tip is above the pip"), which silently required the hand to be
 * pointing roughly upward. Point the finger downward — as happens naturally when sweeping a hand
 * downward — and the tip falls below the pip, the finger reads as curled, and POINT stops being
 * recognised at all. That is why scrolling *down* with the air trackpad failed while every other
 * direction worked. Distance from the wrist carries the same meaning without caring which way
 * the hand is oriented.
 *
 * Squared distances are compared directly; the square root would be the same monotonic ordering
 * for more work, on a function that runs per finger per frame.
 */
private fun fingerExtended(landmarks: List<Point>, tip: Int, pip: Int): Boolean {
    val wrist = landmarks[WRIST]
    return distanceSquared(landmarks[tip], wrist) > distanceSquared(landmarks[pip], wrist)
}

/**
 * How much further than its own middle joint the middle finger must reach before a hand counts
 * as a peace sign rather than a point.
 *
 * Without a margin the two poses share a boundary at exactly ratio 1.0, and a pointing hand does
 * not hold its middle finger fully curled — it sits a little proud of the knuckle, wandering
 * either side of that line frame to frame. The classification then alternates between POINT and
 * PEACE, which is felt as the cursor stalling and something being selected while you were only
 * moving it. A real peace sign clears this several times over.
 */
private const val PEACE_MIDDLE_MARGIN = 0.15f

/**
 * How far the tip reaches from the wrist, relative to its own middle joint.
 *
 * Above 1.0 the finger is extended, below it the tip has folded back toward the palm. A ratio
 * rather than a flag, so a caller that needs to be *sure* — rather than merely on the right side
 * of the line — can ask for a margin. Compared as squares, so no square root is taken on the
 * per-frame path.
 */
private fun fingerReachesBeyond(landmarks: List<Point>, tip: Int, pip: Int, margin: Float): Boolean {
    val wrist = landmarks[WRIST]
    val pipDistanceSquared = distanceSquared(landmarks[pip], wrist)
    // Degenerate: the joint is on top of the wrist, so there is no direction to be extended in.
    if (pipDistanceSquared <= 0f) return false
    val threshold = (1f + margin) * (1f + margin)
    return distanceSquared(landmarks[tip], wrist) > pipDistanceSquared * threshold
}

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
        // A margin, not just the flag: see PEACE_MIDDLE_MARGIN. A middle finger only barely past
        // its knuckle belongs to a pointing hand, and calling it a peace sign is how the pointer
        // ends up clicking when the user meant to move.
        index && middle && !ring && !pinky ->
            if (fingerReachesBeyond(landmarks, 12, 10, PEACE_MIDDLE_MARGIN)) {
                Gesture.PEACE
            } else {
                Gesture.POINT
            }
        index && !middle && !ring && !pinky -> Gesture.POINT
        else -> Gesture.UNKNOWN
    }
}
