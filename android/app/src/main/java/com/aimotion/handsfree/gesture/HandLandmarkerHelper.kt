package com.aimotion.handsfree.gesture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.net.URL

private const val TAG = "HandLandmarkerHelper"
private const val MODEL_URL =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
private const val MODEL_FILE_NAME = "hand_landmarker.task"

/** Downloads (once) and wraps MediaPipe's Hand Landmarker task for on-device, real-time
 * detection — frames never leave the phone. */
class HandLandmarkerHelper(
    context: Context,
    private val onResult: (HandLandmarkerResult) -> Unit,
) {
    private val landmarker: HandLandmarker

    init {
        val modelFile = ensureModel(context)

        fun build(delegate: Delegate) = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            // Only the first hand is ever read (see toGestures and the trackpad/pinch tracking),
            // so detecting one instead of two is free latency.
            .setNumHands(1)
            // Lowered from 0.6: the stable-frame debounce downstream is what rejects false
            // positives, so a stricter detector here only costs missed hands. Presence and
            // tracking thresholds keep the landmarks steady between detections.
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "detect error", e) }
            .build()

        // GPU inference is markedly faster, but the delegate fails to initialise on some
        // drivers/emulators — fall back rather than leaving gesture control dead.
        landmarker = try {
            HandLandmarker.createFromOptions(context, build(Delegate.GPU))
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
            HandLandmarker.createFromOptions(context, build(Delegate.CPU))
        }
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    fun close() = landmarker.close()

    companion object {
        private fun ensureModel(context: Context): File {
            val file = File(context.filesDir, MODEL_FILE_NAME)
            if (!file.exists()) {
                Log.i(TAG, "downloading hand landmarker model")
                URL(MODEL_URL).openStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return file
        }
    }
}

fun HandLandmarkerResult.toGestures(): List<Pair<Gesture, Handedness>> {
    val results = mutableListOf<Pair<Gesture, Handedness>>()
    for (i in landmarks().indices) {
        val handedness = if (handedness().getOrNull(i)?.firstOrNull()?.categoryName() == "Left") {
            Handedness.LEFT
        } else {
            Handedness.RIGHT
        }
        val points = landmarks()[i].map { Point(it.x(), it.y(), it.z()) }
        results.add(classifyGesture(points, handedness) to handedness)
    }
    return results
}
