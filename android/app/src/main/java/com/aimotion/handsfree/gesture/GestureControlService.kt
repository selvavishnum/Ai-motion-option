package com.aimotion.handsfree.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.aimotion.handsfree.MainActivity
import com.aimotion.handsfree.R
import java.util.concurrent.Executors

private const val TAG = "GestureControlService"
private const val CHANNEL_ID = "gesture_control"
private const val NOTIFICATION_ID = 1
private const val MIN_FRAME_INTERVAL_MS = 180L // ~5 fps, plenty for gesture control
private const val STABLE_FRAMES_REQUIRED = 3
private const val ACTION_COOLDOWN_MS = 900L

/** Foreground service that keeps the front camera running while other apps are on screen,
 * classifies the gesture in each frame on-device, and — once the same gesture has been seen
 * for a few consecutive frames (debounce, avoids misfires) and the cooldown has elapsed
 * (avoids repeat-firing) — dispatches the mapped action via [ActionDispatcher]. */
class GestureControlService : LifecycleService() {

    private lateinit var mappingStore: GestureMappingStore
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var lastFrameAtMs = 0L
    private var lastFiredAtMs = 0L
    private var candidateGesture: Gesture = Gesture.UNKNOWN
    private var candidateStreak = 0

    override fun onCreate() {
        super.onCreate()
        mappingStore = GestureMappingStore(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        handLandmarkerHelper = HandLandmarkerHelper(this) { result -> onLandmarkResult(result) }
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        handLandmarkerHelper?.close()
        analysisExecutor.shutdown()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAtMs < MIN_FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastFrameAtMs = now
        val bitmap: Bitmap = imageProxy.toBitmap()
        handLandmarkerHelper?.detectAsync(bitmap, now)
        imageProxy.close()
    }

    private fun onLandmarkResult(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult) {
        val gestures = result.toGestures()
        val top = gestures.firstOrNull()?.first ?: Gesture.UNKNOWN
        handleDetectedGesture(top)
    }

    private fun handleDetectedGesture(gesture: Gesture) {
        if (gesture == Gesture.UNKNOWN) {
            candidateGesture = Gesture.UNKNOWN
            candidateStreak = 0
            return
        }
        if (gesture == candidateGesture) {
            candidateStreak++
        } else {
            candidateGesture = gesture
            candidateStreak = 1
        }
        if (candidateStreak < STABLE_FRAMES_REQUIRED) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastFiredAtMs < ACTION_COOLDOWN_MS) return

        val action = mappingStore.load()[gesture] ?: return
        lastFiredAtMs = now
        candidateStreak = 0
        ActionDispatcher.fire(action)
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GestureControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureControlService::class.java))
        }
    }
}
