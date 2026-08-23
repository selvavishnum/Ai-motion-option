package com.aimotion.handsfree.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.aimotion.handsfree.face.FaceGesture
import com.aimotion.handsfree.face.FaceLandmarkerHelper
import com.aimotion.handsfree.face.FaceMappingStore
import com.aimotion.handsfree.face.blendshapeMap
import com.aimotion.handsfree.face.classifyFaceGesture
import com.aimotion.handsfree.face.classifyGaze
import com.aimotion.handsfree.face.gazeOffset
import com.aimotion.handsfree.overlay.OverlayBubbleService
import java.util.concurrent.Executors

private const val TAG = "GestureControlService"
private const val CHANNEL_ID = "gesture_control"
private const val NOTIFICATION_ID = 1
private const val MIN_FRAME_INTERVAL_MS = 180L // ~5 fps, plenty for gesture control
// Debounce for the "big" discrete actions (Home, Back, wake, wink, ...): fast enough to feel
// responsive, but still requires two consecutive matching frames + a cooldown so a hand/face
// passing briefly through a pose mid-motion can't misfire something disruptive.
private const val STABLE_FRAMES_REQUIRED = 2
private const val ACTION_COOLDOWN_MS = 600L

// Pinch-to-zoom and the finger-trackpad (below) are continuous motions, not static poses, so
// they run on their own faster, separate cooldowns — they're meant to feel like actually
// dragging/scrolling, not like a discrete, debounced button press.
private const val PINCH_DISTANCE_DELTA_THRESHOLD = 0.035f // normalized (0..1) coordinate space
private const val PINCH_COOLDOWN_MS = 220L

private const val TRACKPAD_MOVE_THRESHOLD = 0.045f // normalized (0..1) coordinate space
private const val TRACKPAD_COOLDOWN_MS = 200L
private const val TRACKPAD_HOLD_FRAMES_FOR_TAP = 2

/** Foreground service that keeps the front camera running while other apps are on screen,
 * classifies the gesture in each frame on-device, and — once the same gesture has been seen
 * for a few consecutive frames (debounce, avoids misfires) and the cooldown has elapsed
 * (avoids repeat-firing) — dispatches the mapped action via [ActionDispatcher]. */
class GestureControlService : LifecycleService() {

    private lateinit var mappingStore: GestureMappingStore
    private lateinit var faceMappingStore: FaceMappingStore
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var lastFrameAtMs = 0L
    private var frameCounter = 0
    private var lastFiredAtMs = 0L
    private var candidateGesture: Gesture = Gesture.UNKNOWN
    private var candidateStreak = 0

    private var lastPinchDistance: Float? = null
    private var lastPinchFiredAtMs = 0L

    private var lastPointPos: Pair<Float, Float>? = null
    private var pointHoldStreak = 0
    private var lastTrackpadFiredAtMs = 0L

    private var lastFaceFiredAtMs = 0L
    private var candidateFaceGesture: FaceGesture? = null
    private var candidateFaceStreak = 0

    override fun onCreate() {
        super.onCreate()
        mappingStore = GestureMappingStore(this)
        faceMappingStore = FaceMappingStore(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        initDetectorsAsync()
        startCamera()
        // Ensure the status bubble is showing whenever gesture control is, not only when
        // MainActivity happens to be open — a no-op if the overlay permission isn't granted.
        OverlayBubbleService.start(this)
    }

    /** Each helper downloads its model file the first time it's constructed — blocking network
     * I/O, which is illegal on the main thread (StrictMode ends the process with
     * NetworkOnMainThreadException). Building them here used to happen inline in [onCreate], so
     * on a fresh install the service died before it ever read a frame. Constructing them on
     * [analysisExecutor] fixes that and needs no locking: it is single-threaded and
     * [analyzeFrame] runs on it too, so this task always completes before the first frame. */
    private fun initDetectorsAsync() {
        analysisExecutor.execute {
            try {
                handLandmarkerHelper = HandLandmarkerHelper(this) { result -> onLandmarkResult(result) }
                faceLandmarkerHelper = FaceLandmarkerHelper(this) { result -> onFaceResult(result) }
            } catch (e: Exception) {
                // Usually "first run with no connectivity". Frames then no-op until the service
                // is restarted with a network, instead of taking the whole process down.
                Log.e(TAG, "failed to initialise detectors", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        // Queued rather than closed inline: the helpers are owned by analysisExecutor (see
        // initDetectorsAsync), and stopping mid-download would otherwise leak a landmarker that
        // hadn't been assigned yet. shutdown() still lets this last task run.
        analysisExecutor.execute {
            handLandmarkerHelper?.close()
            faceLandmarkerHelper?.close()
            handLandmarkerHelper = null
            faceLandmarkerHelper = null
        }
        analysisExecutor.shutdown()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val bitmap: Bitmap = try {
            imageProxy.toUprightMirroredBitmap()
        } finally {
            imageProxy.close()
        }
        // Alternate hand/face detection per frame rather than running both models every frame —
        // keeps per-frame cost down on mid-range phones while still updating each ~2-3x/sec,
        // plenty for discrete gesture/expression debouncing.
        frameCounter++
        if (frameCounter % 2 == 0) {
            handLandmarkerHelper?.detectAsync(bitmap, now)
        } else {
            faceLandmarkerHelper?.detectAsync(bitmap, now)
        }
    }

    /** CameraX hands back the raw sensor buffer, and [ImageProxy.toBitmap] does **not** apply
     * [androidx.camera.core.ImageInfo.rotationDegrees] — so on a phone held upright the frame
     * arrives rotated 90°. That silently broke every hand gesture: the classifier decides a
     * finger is "extended" by comparing its tip against its knuckle on the image Y axis
     * (see Gesture.kt), so in a sideways frame those comparisons measure the wrong axis and
     * nearly every pose collapses to UNKNOWN. Face gestures kept working through the same bug
     * because blendshape scores are computed in the face's own coordinate frame, making them
     * rotation-invariant — which is exactly why hand and face behaved differently on-device.
     *
     * The frame is also mirrored, matching how a front camera is conventionally previewed: with
     * it, moving your hand to your right moves it right within the frame, so the air-trackpad's
     * swipe directions and the gaze left/right offsets follow what you actually see. */
    private fun ImageProxy.toUprightMirroredBitmap(): Bitmap {
        val raw = toBitmap()
        val matrix = Matrix().apply {
            postRotate(imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun onLandmarkResult(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult) {
        val gestures = result.toGestures()
        val top = gestures.firstOrNull()?.first ?: Gesture.UNKNOWN

        when (top) {
            // A single extended index finger drives the continuous mini-trackpad below instead
            // of a fixed discrete action, so it's kept out of the discrete debounce entirely.
            Gesture.POINT -> {
                candidateGesture = Gesture.UNKNOWN
                candidateStreak = 0
                lastPinchDistance = null
                handleFingerTrackpad(result)
            }
            Gesture.UNKNOWN -> {
                handleDetectedGesture(Gesture.UNKNOWN)
                handlePinchTracking(result)
                resetTrackpad()
            }
            else -> {
                handleDetectedGesture(top)
                lastPinchDistance = null
                resetTrackpad()
            }
        }
    }

    /** A single extended index finger acts as an air mini-trackpad: move it to swipe (turn
     * left/right, scroll up/down), hold it still to tap/select — a continuous motion, so it
     * runs on its own fast cooldown rather than the discrete-gesture debounce, and fires fixed
     * actions directly (not through the remappable mapping table). Direction sign (which way is
     * "right") depends on whether the camera frame the landmarks come from is mirrored; verify
     * on-device and flip the two branches below if turn/scroll feels reversed. */
    private fun handleFingerTrackpad(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult) {
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null || landmarks.size < 9) {
            resetTrackpad()
            return
        }
        val tip = landmarks[8]
        val pos = tip.x() to tip.y()
        val previous = lastPointPos
        lastPointPos = pos
        if (previous == null) {
            pointHoldStreak = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastTrackpadFiredAtMs < TRACKPAD_COOLDOWN_MS) return

        val dx = pos.first - previous.first
        val dy = pos.second - previous.second
        val movement = kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy))

        if (movement < TRACKPAD_MOVE_THRESHOLD) {
            pointHoldStreak++
            if (pointHoldStreak == TRACKPAD_HOLD_FRAMES_FOR_TAP) {
                lastTrackpadFiredAtMs = now
                ActionDispatcher.fire(GestureAction(ActionType.TAP))
            }
            return
        }
        pointHoldStreak = 0

        val action = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx > 0) ActionType.SWIPE_RIGHT else ActionType.SWIPE_LEFT
        } else {
            if (dy > 0) ActionType.SWIPE_DOWN else ActionType.SWIPE_UP
        }
        lastTrackpadFiredAtMs = now
        ActionDispatcher.fire(GestureAction(action))
    }

    private fun resetTrackpad() {
        lastPointPos = null
        pointHoldStreak = 0
    }

    private fun handlePinchTracking(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult) {
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null || landmarks.size < 9) {
            lastPinchDistance = null
            return
        }
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val dx = thumbTip.x() - indexTip.x()
        val dy = thumbTip.y() - indexTip.y()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        val previous = lastPinchDistance
        lastPinchDistance = distance
        if (previous == null) return

        val delta = distance - previous
        if (kotlin.math.abs(delta) < PINCH_DISTANCE_DELTA_THRESHOLD) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastPinchFiredAtMs < PINCH_COOLDOWN_MS) return

        lastPinchFiredAtMs = now
        ActionDispatcher.pinch(zoomIn = delta > 0)
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

    private fun onFaceResult(result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult) {
        val blendshapes = result.blendshapeMap()
        // Blendshape-based expressions (blink, wink, eyebrows, mouth, smile) take priority —
        // they're a more reliable signal than gaze, which only kicks in when nothing else fired.
        val detected = blendshapes?.let { classifyFaceGesture(it) }
            ?: result.gazeOffset()?.let { classifyGaze(it) }
        handleDetectedFaceGesture(detected)
    }

    private fun handleDetectedFaceGesture(gesture: FaceGesture?) {
        if (gesture == null) {
            candidateFaceGesture = null
            candidateFaceStreak = 0
            return
        }
        if (gesture == candidateFaceGesture) {
            candidateFaceStreak++
        } else {
            candidateFaceGesture = gesture
            candidateFaceStreak = 1
        }
        if (candidateFaceStreak < STABLE_FRAMES_REQUIRED) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastFaceFiredAtMs < ACTION_COOLDOWN_MS) return

        val action = faceMappingStore.load()[gesture] ?: return
        lastFaceFiredAtMs = now
        candidateFaceStreak = 0
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
