package com.aimotion.handsfree.face

import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.GestureAction

/** Face expressions detected from MediaPipe Face Landmarker's blendshape scores (0..1 per
 * expression). Checked in priority order below — a face is rarely doing only one thing, so the
 * first threshold crossed wins, same pattern as the hand-pose classifier. */
enum class FaceGesture(val label: String) {
    BLINK("blink"),
    EYEBROWS_UP("eyebrows_up"),
    MOUTH_OPEN("mouth_open"),
    SMILE("smile"),
}

private const val BLINK_THRESHOLD = 0.6f
private const val EYEBROWS_THRESHOLD = 0.5f
private const val MOUTH_OPEN_THRESHOLD = 0.5f
private const val SMILE_THRESHOLD = 0.6f

/** [blendshapes] keys are MediaPipe's standard ARKit-style blendshape names, e.g.
 * "eyeBlinkLeft", "jawOpen", "mouthSmileLeft". Missing keys score 0. */
fun classifyFaceGesture(blendshapes: Map<String, Float>): FaceGesture? {
    fun score(name: String) = blendshapes[name] ?: 0f
    fun avg(a: String, b: String) = (score(a) + score(b)) / 2f

    val blink = avg("eyeBlinkLeft", "eyeBlinkRight")
    val browsUp = avg("browOuterUpLeft", "browOuterUpRight")
    val mouthOpen = score("jawOpen")
    val smile = avg("mouthSmileLeft", "mouthSmileRight")

    return when {
        blink > BLINK_THRESHOLD -> FaceGesture.BLINK
        browsUp > EYEBROWS_THRESHOLD -> FaceGesture.EYEBROWS_UP
        mouthOpen > MOUTH_OPEN_THRESHOLD -> FaceGesture.MOUTH_OPEN
        smile > SMILE_THRESHOLD -> FaceGesture.SMILE
        else -> null
    }
}

/** Default preset — deliberately distinct from the hand-gesture defaults so both modalities
 * can run at once without colliding. Remappable in Settings like hand gestures. */
val DEFAULT_FACE_MAPPING: Map<FaceGesture, GestureAction> = mapOf(
    FaceGesture.BLINK to GestureAction(ActionType.TAP),
    FaceGesture.EYEBROWS_UP to GestureAction(ActionType.BACK),
    FaceGesture.MOUTH_OPEN to GestureAction(ActionType.HOME),
    FaceGesture.SMILE to GestureAction(ActionType.RECENTS),
)
