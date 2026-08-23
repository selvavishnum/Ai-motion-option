package com.aimotion.handsfree.face

import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.GestureAction

/** Face expressions detected from MediaPipe Face Landmarker's blendshape scores (0..1 per
 * expression) — except [GAZE_LEFT]/[GAZE_RIGHT], which come from raw iris/eye-corner landmark
 * positions instead (see [classifyGaze]), computed in GestureControlService next to the
 * equivalent pinch-tracking code, not here. "Left"/"right" for [WINK_LEFT]/[WINK_RIGHT] follow
 * MediaPipe's own eyeBlinkLeft/eyeBlinkRight naming (your own anatomical left/right eye). */
enum class FaceGesture(val label: String) {
    BLINK("blink"),
    WINK_LEFT("wink_left"),
    WINK_RIGHT("wink_right"),
    EYEBROWS_UP("eyebrows_up"),
    EYEBROWS_DOWN("eyebrows_down"),
    MOUTH_OPEN("mouth_open"),
    SMILE("smile"),
    GAZE_LEFT("gaze_left"),
    GAZE_RIGHT("gaze_right"),
}

private const val WINK_CLOSED_THRESHOLD = 0.6f
private const val WINK_OTHER_EYE_OPEN_THRESHOLD = 0.3f
private const val BLINK_THRESHOLD = 0.6f
private const val EYEBROWS_THRESHOLD = 0.5f
private const val MOUTH_OPEN_THRESHOLD = 0.5f
private const val SMILE_THRESHOLD = 0.6f

/**
 * The blendshape scores this classifier consults, already averaged across left/right where the
 * expression is symmetric. Extracted from MediaPipe's 52 categories in a single pass — see
 * `FaceLandmarkerResult.faceSignals()`.
 */
data class FaceSignals(
    val leftBlink: Float,
    val rightBlink: Float,
    val browsUp: Float,
    val browsDown: Float,
    val mouthOpen: Float,
    val smile: Float,
)

/** [blendshapes] keys are MediaPipe's standard ARKit-style blendshape names, e.g.
 * "eyeBlinkLeft", "jawOpen", "mouthSmileLeft". Missing keys score 0. Kept for callers holding a
 * map; the frame loop uses the [FaceSignals] overload, which never builds one. */
fun classifyFaceGesture(blendshapes: Map<String, Float>): FaceGesture? {
    fun score(name: String) = blendshapes[name] ?: 0f
    fun avg(a: String, b: String) = (score(a) + score(b)) / 2f

    return classifyFaceGesture(
        FaceSignals(
            leftBlink = score("eyeBlinkLeft"),
            rightBlink = score("eyeBlinkRight"),
            browsUp = avg("browOuterUpLeft", "browOuterUpRight"),
            browsDown = avg("browDownLeft", "browDownRight"),
            mouthOpen = score("jawOpen"),
            smile = avg("mouthSmileLeft", "mouthSmileRight"),
        )
    )
}

/** Checked in priority order: a wink (one eye closed, the other clearly open) is tested before a
 * full blink, since a wink is a stricter subset of "some blink signal present". */
fun classifyFaceGesture(signals: FaceSignals): FaceGesture? {
    val leftBlink = signals.leftBlink
    val rightBlink = signals.rightBlink
    val browsUp = signals.browsUp
    val browsDown = signals.browsDown
    val mouthOpen = signals.mouthOpen
    val smile = signals.smile

    return when {
        leftBlink > WINK_CLOSED_THRESHOLD && rightBlink < WINK_OTHER_EYE_OPEN_THRESHOLD -> FaceGesture.WINK_LEFT
        rightBlink > WINK_CLOSED_THRESHOLD && leftBlink < WINK_OTHER_EYE_OPEN_THRESHOLD -> FaceGesture.WINK_RIGHT
        leftBlink > BLINK_THRESHOLD && rightBlink > BLINK_THRESHOLD -> FaceGesture.BLINK
        browsUp > EYEBROWS_THRESHOLD -> FaceGesture.EYEBROWS_UP
        browsDown > EYEBROWS_THRESHOLD -> FaceGesture.EYEBROWS_DOWN
        mouthOpen > MOUTH_OPEN_THRESHOLD -> FaceGesture.MOUTH_OPEN
        smile > SMILE_THRESHOLD -> FaceGesture.SMILE
        else -> null
    }
}

private const val GAZE_OFFSET_THRESHOLD = 0.35f

/** [avgIrisOffset] is a signed, eye-width-normalized offset (roughly -1..1) of the iris from
 * its eye's own center, averaged across both eyes — the caller computes this from raw landmark
 * positions (iris center vs. the eye's two corners), since that keeps the sign meaningful
 * regardless of which corner landmark is "outer" vs "inner". Gaze tracking is inherently
 * noisier than blendshapes at low frame rates; treat this as best-effort, and if left/right
 * come out swapped on a real device, flip the two branches below. */
fun classifyGaze(avgIrisOffset: Float): FaceGesture? = when {
    avgIrisOffset < -GAZE_OFFSET_THRESHOLD -> FaceGesture.GAZE_LEFT
    avgIrisOffset > GAZE_OFFSET_THRESHOLD -> FaceGesture.GAZE_RIGHT
    else -> null
}

/** Default preset. Every entry is remappable in Settings, independent of the hand-gesture
 * mapping so the two modalities never collide. */
val DEFAULT_FACE_MAPPING: Map<FaceGesture, GestureAction> = mapOf(
    FaceGesture.BLINK to GestureAction(ActionType.TAP),
    FaceGesture.WINK_LEFT to GestureAction(ActionType.BACK),
    FaceGesture.WINK_RIGHT to GestureAction(ActionType.HOME),
    FaceGesture.EYEBROWS_UP to GestureAction(ActionType.SWIPE_UP),
    FaceGesture.EYEBROWS_DOWN to GestureAction(ActionType.SWIPE_DOWN),
    FaceGesture.MOUTH_OPEN to GestureAction(ActionType.RECENTS),
    FaceGesture.SMILE to GestureAction(ActionType.TAP),
    FaceGesture.GAZE_LEFT to GestureAction(ActionType.SWIPE_LEFT),
    FaceGesture.GAZE_RIGHT to GestureAction(ActionType.SWIPE_RIGHT),
)
