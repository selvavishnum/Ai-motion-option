package com.aimotion.handsfree.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.File
import java.net.URL

private const val TAG = "FaceLandmarkerHelper"
private const val MODEL_URL =
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
private const val MODEL_FILE_NAME = "face_landmarker.task"

/** Downloads (once) and wraps MediaPipe's Face Landmarker task, with blendshapes enabled, for
 * on-device face-expression detection (blink, mouth open, smile, eyebrows up). */
class FaceLandmarkerHelper(
    context: Context,
    private val onResult: (FaceLandmarkerResult) -> Unit,
) : com.aimotion.handsfree.gesture.FrameDetector {
    private val landmarker: FaceLandmarker

    init {
        val modelFile = ensureModel(context)

        fun build(delegate: Delegate) = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "detect error", e) }
            .build()

        // See HandLandmarkerHelper: GPU where available, CPU where the delegate won't start.
        landmarker = try {
            FaceLandmarker.createFromOptions(context, build(Delegate.GPU))
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
            FaceLandmarker.createFromOptions(context, build(Delegate.CPU))
        }
    }

    override fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    override fun close() = landmarker.close()

    companion object {
        private fun ensureModel(context: Context): File {
            val file = File(context.filesDir, MODEL_FILE_NAME)
            if (!file.exists()) {
                Log.i(TAG, "downloading face landmarker model")
                URL(MODEL_URL).openStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return file
        }
    }
}

/** Blendshape category name -> score (0..1) for the first detected face, or null if no face /
 * blendshapes weren't produced. Convenient for inspection and tests; the frame loop uses
 * [faceSignals], which avoids building the map at all. */
fun FaceLandmarkerResult.blendshapeMap(): Map<String, Float>? {
    val shapes = faceBlendshapes().orElse(null)?.firstOrNull() ?: return null
    return shapes.associate { it.categoryName() to it.score() }
}

/**
 * The six blendshape scores the classifier actually consults, gathered in one pass.
 *
 * MediaPipe emits **52** blendshape categories per face. [blendshapeMap] turned all of them into
 * a `Map` — 52 `Pair`s plus a `LinkedHashMap` — every frame, so that [classifyFaceGesture] could
 * look up six keys and ignore the rest. This walks the list once and keeps only what's used,
 * trading that per-frame map for a single small object.
 */
fun FaceLandmarkerResult.faceSignals(): FaceSignals? {
    val shapes = faceBlendshapes().orElse(null)?.firstOrNull() ?: return null

    var leftBlink = 0f
    var rightBlink = 0f
    var browUpLeft = 0f
    var browUpRight = 0f
    var browDownLeft = 0f
    var browDownRight = 0f
    var mouthOpen = 0f
    var smileLeft = 0f
    var smileRight = 0f

    for (category in shapes) {
        when (category.categoryName()) {
            "eyeBlinkLeft" -> leftBlink = category.score()
            "eyeBlinkRight" -> rightBlink = category.score()
            "browOuterUpLeft" -> browUpLeft = category.score()
            "browOuterUpRight" -> browUpRight = category.score()
            "browDownLeft" -> browDownLeft = category.score()
            "browDownRight" -> browDownRight = category.score()
            "jawOpen" -> mouthOpen = category.score()
            "mouthSmileLeft" -> smileLeft = category.score()
            "mouthSmileRight" -> smileRight = category.score()
        }
    }

    return FaceSignals(
        leftBlink = leftBlink,
        rightBlink = rightBlink,
        browsUp = (browUpLeft + browUpRight) / 2f,
        browsDown = (browDownLeft + browDownRight) / 2f,
        mouthOpen = mouthOpen,
        smile = (smileLeft + smileRight) / 2f,
    )
}

// Standard MediaPipe Face Landmarker topology indices (478-point mesh, iris included).
private const val LEFT_EYE_CORNER_A = 33
private const val LEFT_EYE_CORNER_B = 133
private const val LEFT_IRIS_CENTER = 468
private const val RIGHT_EYE_CORNER_A = 263
private const val RIGHT_EYE_CORNER_B = 362
private const val RIGHT_IRIS_CENTER = 473

/** Nose-tip index in the 478-point mesh, used as the head's position. */
private const val NOSE_TIP = 1

/**
 * Where the head is, plus a scale reference.
 *
 * @param scale distance between the outer eye corners. Dividing displacement by this makes head
 *   movement mean the same thing whether you're sitting close to the phone or holding it at
 *   arm's length — without it, the same real movement registers as several times larger up close.
 */
data class HeadPoint(val x: Float, val y: Float, val scale: Float)

/** The head's current position, or null when no face is visible. Tracked as *movement* rather
 * than a pose: it's the change in this point over time that becomes a gesture. */
fun FaceLandmarkerResult.headPoint(): HeadPoint? {
    val landmarks = faceLandmarks().firstOrNull() ?: return null
    if (landmarks.size <= RIGHT_EYE_CORNER_A) return null

    val nose = landmarks[NOSE_TIP]
    val leftCorner = landmarks[LEFT_EYE_CORNER_A]
    val rightCorner = landmarks[RIGHT_EYE_CORNER_A]
    val dx = rightCorner.x() - leftCorner.x()
    val dy = rightCorner.y() - leftCorner.y()
    val scale = kotlin.math.sqrt(dx * dx + dy * dy)

    return HeadPoint(nose.x(), nose.y(), scale)
}

/** Signed, eye-width-normalized average horizontal iris offset across both eyes (see
 * [classifyGaze]), or null if the face/expected landmark indices aren't present. */
fun FaceLandmarkerResult.gazeOffset(): Float? {
    val landmarks = faceLandmarks().firstOrNull() ?: return null
    if (landmarks.size <= RIGHT_IRIS_CENTER) return null

    fun eyeOffset(cornerA: Int, cornerB: Int, iris: Int): Float {
        val ax = landmarks[cornerA].x()
        val bx = landmarks[cornerB].x()
        val center = (ax + bx) / 2f
        val width = kotlin.math.abs(bx - ax).coerceAtLeast(1e-4f)
        return (landmarks[iris].x() - center) / (width / 2f)
    }

    val leftOffset = eyeOffset(LEFT_EYE_CORNER_A, LEFT_EYE_CORNER_B, LEFT_IRIS_CENTER)
    val rightOffset = eyeOffset(RIGHT_EYE_CORNER_A, RIGHT_EYE_CORNER_B, RIGHT_IRIS_CENTER)
    return (leftOffset + rightOffset) / 2f
}
