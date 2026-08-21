package com.aimotion.handsfree.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
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
) {
    private val landmarker: FaceLandmarker

    init {
        val modelFile = ensureModel(context)
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelFile.absolutePath).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "detect error", e) }
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    fun close() = landmarker.close()

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
 * blendshapes weren't produced. */
fun FaceLandmarkerResult.blendshapeMap(): Map<String, Float>? {
    val shapes = faceBlendshapes().orElse(null)?.firstOrNull() ?: return null
    return shapes.associate { it.categoryName() to it.score() }
}
