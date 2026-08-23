package com.aimotion.handsfree.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.aimotion.handsfree.face.classifyFaceGesture
import com.aimotion.handsfree.face.faceSignals
import com.aimotion.handsfree.face.classifyGaze
import com.aimotion.handsfree.face.gazeOffset
import com.aimotion.handsfree.overlay.OverlayBubbleService
import java.util.concurrent.Executors

private const val TAG = "GestureControlService"
private const val CHANNEL_ID = "gesture_control"
private const val NOTIFICATION_ID = 1
// Frame budget. This single number dominates how quickly any gesture can possibly fire, because
// the debounce below counts *frames*: at the old 180ms (5.5fps, halved again to 2.8fps per
// detector by alternating) a two-frame debounce meant a ~720ms wait before anything happened.
// At 60ms a detector sees a frame every 60-120ms depending on whether the other one is also on,
// so a three-frame debounce now costs ~180-360ms while sampling motion far more densely — both
// faster *and* more accurate than before. Frames the detector can't keep up with are dropped by
// STRATEGY_KEEP_ONLY_LATEST, so this self-regulates on slower phones.
private const val MIN_FRAME_INTERVAL_MS = 60L

// Idle throttling. Running the detector at full rate while nothing is in front of the camera is
// the single largest avoidable battery cost here: most of the day there is no hand and no face,
// yet every frame still paid for a bitmap copy and a model inference. After
// [IDLE_AFTER_MS] with nothing detected the loop drops to a slow watch rate, and the moment a
// hand or face appears — before any pose is even formed — it snaps back to full speed. The cost
// is up to one extra [IDLE_FRAME_INTERVAL_MS] before the first gesture of a session registers;
// everything after that is unaffected.
private const val IDLE_FRAME_INTERVAL_MS = 300L
private const val IDLE_AFTER_MS = 5_000L

// Debounce for the "big" discrete actions (Home, Back, lock, wink, ...). Raised from 2 to 3
// frames: the higher frame rate buys enough headroom to demand more confirmation than before
// while still cutting the wall-clock latency several times over, which is what stops a hand or
// face passing through a pose mid-motion from misfiring something disruptive.
private const val STABLE_FRAMES_REQUIRED = 3
private const val ACTION_COOLDOWN_MS = 350L

// Pinch-to-zoom and the finger-trackpad (below) are continuous motions, not static poses, so
// they run on their own faster, separate cooldowns — they're meant to feel like actually
// dragging/scrolling, not like a discrete, debounced button press.
private const val PINCH_DISTANCE_DELTA_THRESHOLD = 0.030f // normalized (0..1) coordinate space
private const val PINCH_COOLDOWN_MS = 90L

// Threshold lowered alongside the frame interval: frames are ~3x closer together now, so a hand
// moving at the same real-world speed travels proportionally less between them.
private const val TRACKPAD_MOVE_THRESHOLD = 0.018f // normalized (0..1) coordinate space
private const val TRACKPAD_COOLDOWN_MS = 90L
private const val TRACKPAD_HOLD_FRAMES_FOR_TAP = 4

/** Foreground service that keeps the front camera running while other apps are on screen,
 * classifies the gesture in each frame on-device, and — once the same gesture has been seen
 * for a few consecutive frames (debounce, avoids misfires) and the cooldown has elapsed
 * (avoids repeat-firing) — dispatches the mapped action via [ActionDispatcher]. */
class GestureControlService : LifecycleService() {

    private lateinit var mappingStore: GestureMappingStore
    private lateinit var faceMappingStore: FaceMappingStore
    private lateinit var toggles: GestureToggleStore
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var lastFrameAtMs = 0L
    private var frameCounter = 0

    /** When a hand or face was last actually visible. Drives the idle throttle above. Written
     * from the MediaPipe callback threads and read on the analysis thread, hence volatile. */
    @Volatile
    private var lastSubjectSeenAtMs = 0L

    /** Whether a camera use case is currently bound. Tracked so the screen-off release and the
     * screen-on rebind can't double-bind or unbind nothing. */
    private var cameraBound = false

    // Confined to analysisExecutor; see toUprightMirroredBitmap.
    private var cachedMatrix: Matrix? = null
    private var cachedRotation = Int.MIN_VALUE
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
        toggles = GestureToggleStore(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        initDetectorsAsync()
        startCamera()
        // ACTION_SCREEN_ON/OFF are only deliverable to a runtime-registered receiver; they
        // cannot be declared in the manifest.
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Ensure the status bubble is showing whenever gesture control is, not only when
        // MainActivity happens to be open — a no-op if the overlay permission isn't granted,
        // and skipped entirely when the user has hidden the dot.
        if (toggles.bubbleEnabled) OverlayBubbleService.start(this)
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
        runCatching { unregisterReceiver(screenStateReceiver) }
        cameraProvider?.unbindAll()
        cameraBound = false
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
        if (cameraBound) return
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
                cameraBound = true
            } catch (e: Exception) {
                Log.e(TAG, "failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Releases the camera without stopping the service.
     *
     * Skipping frames is not enough to save power: the sensor keeps streaming and stays powered
     * either way. Unbinding is what actually lets the camera hardware shut down, which is the
     * whole point when the screen is off and nobody can see the result anyway.
     */
    private fun releaseCamera() {
        if (!cameraBound) return
        cameraProvider?.unbindAll()
        cameraBound = false
        // Next resume should start at full rate rather than inheriting a stale idle timer.
        lastSubjectSeenAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * With the screen off the phone is usually in a pocket or face down, so the camera is
     * filming nothing while the detector burns battery on it. Releasing the camera there is the
     * largest power saving available to this app.
     *
     * The cost is that a gesture cannot wake the screen, since nothing is watching — which is
     * why this is a user-visible setting rather than unconditional behaviour.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> if (toggles.pauseWhenScreenOff) releaseCamera()
                Intent.ACTION_SCREEN_ON -> startCamera()
            }
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        // Slow down while nothing is in view; full rate resumes the instant something appears.
        val interval = if (now - lastSubjectSeenAtMs > IDLE_AFTER_MS) {
            IDLE_FRAME_INTERVAL_MS
        } else {
            MIN_FRAME_INTERVAL_MS
        }
        if (now - lastFrameAtMs < interval) {
            imageProxy.close()
            return
        }
        lastFrameAtMs = now

        // Pick the consumer BEFORE preparing the frame. Running both models on every frame is too
        // expensive for a mid-range phone, so when both are enabled they alternate — but when
        // only one is on it gets *every* frame, halving how long that modality takes to
        // recognise a gesture. That's why the on/off switches are a responsiveness control as
        // much as a preference.
        //
        // Resolving this first also matters for cost: toUprightMirroredBitmap below is a
        // full-resolution copy, and the previous order paid for it on every frame even when both
        // detectors were off or neither had finished initialising, then dropped the result.
        val hand = handLandmarkerHelper.takeIf { toggles.handEnabled }
        val face = faceLandmarkerHelper.takeIf { toggles.faceEnabled }
        frameCounter++
        val consumer: FrameDetector? = when {
            hand != null && face != null -> if (frameCounter % 2 == 0) hand else face
            else -> hand ?: face
        }
        if (consumer == null) {
            imageProxy.close()
            return
        }

        val bitmap: Bitmap = try {
            imageProxy.toUprightMirroredBitmap()
        } finally {
            imageProxy.close()
        }
        consumer.detectAsync(bitmap, now)
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
        val rotation = imageInfo.rotationDegrees

        // The transform only changes when the device rotates, so the Matrix is built once and
        // reused rather than allocated per frame. Safe without synchronisation: analyzeFrame and
        // this function both run on the single-threaded analysisExecutor.
        var matrix = cachedMatrix
        if (matrix == null || rotation != cachedRotation) {
            matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                postScale(-1f, 1f)
            }
            cachedMatrix = matrix
            cachedRotation = rotation
        }

        val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)

        // Two full-resolution bitmaps exist per frame: the buffer copy from CameraX and the
        // transformed result. Only the second is handed to MediaPipe, so releasing the first
        // here frees its pixel memory immediately instead of leaving ~1MB per frame for the
        // collector to find — which, at this frame rate, is the difference between steady state
        // and constant GC pressure.
        //
        // createBitmap may hand back the source unchanged when the transform is a no-op, so
        // identity is checked before recycling something still in use.
        if (transformed !== raw) raw.recycle()

        return transformed
    }

    private fun onLandmarkResult(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult) {
        // Any visible hand lifts the idle throttle, not just a recognised pose — so by the time
        // you have formed a gesture the loop is already back at full rate.
        if (result.landmarks().isNotEmpty()) lastSubjectSeenAtMs = SystemClock.elapsedRealtime()

        // topGesture() rather than toGestures(): the detector tracks a single hand and only the
        // first result was ever read, so building a list and a Pair per frame was garbage.
        val top = result.topGesture()

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
        // Any visible face lifts the idle throttle — see the hand path.
        if (result.faceLandmarks().isNotEmpty()) lastSubjectSeenAtMs = SystemClock.elapsedRealtime()

        // faceSignals() rather than blendshapeMap(): MediaPipe emits 52 blendshape categories and
        // the classifier reads six, so materialising the whole map every frame was waste.
        //
        // Blendshape-based expressions (blink, wink, eyebrows, mouth, smile) take priority —
        // they're a more reliable signal than gaze, which only kicks in when nothing else fired.
        val detected = result.faceSignals()?.let { classifyFaceGesture(it) }
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
