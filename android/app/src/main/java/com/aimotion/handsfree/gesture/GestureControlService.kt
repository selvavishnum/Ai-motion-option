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
import com.aimotion.handsfree.face.headPoint
import com.aimotion.handsfree.overlay.OverlayBubbleService
import com.aimotion.handsfree.overlay.PointerOverlay
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
// Baseline values for sensitivity level 3; SensitivityStore rescales all of them. See
// applySensitivity.
private const val STABLE_FRAMES_REQUIRED = 3
private const val ACTION_COOLDOWN_MS = 350L

// Pinch-to-zoom and the finger-trackpad (below) are continuous motions, not static poses, so
// they run on their own faster, separate cooldowns — they're meant to feel like actually
// dragging/scrolling, not like a discrete, debounced button press.
private const val PINCH_DISTANCE_DELTA_THRESHOLD = 0.030f // normalized (0..1) coordinate space
private const val PINCH_COOLDOWN_MS = 90L

// Displacement accumulated since the last fired swipe, not per-frame delta — see
// DirectionalMotionTracker. Because travel now adds up instead of being sampled, this can be a
// real distance the finger has moved rather than a hair-trigger on one noisy frame.
private const val TRACKPAD_MOVE_THRESHOLD = 0.055f // normalized (0..1) coordinate space
private const val TRACKPAD_COOLDOWN_MS = 90L
private const val TRACKPAD_HOLD_FRAMES_FOR_TAP = 4

/** How many consecutive frames without a recognised POINT before a trackpad stroke is abandoned.
 * See maybeResetTrackpad — a single flickered frame used to discard the whole accumulated
 * movement. */
private const val TRACKPAD_DROPOUT_GRACE_FRAMES = 3

// Air pointer. Your hand only travels comfortably across the middle of the camera's view, so
// that band is stretched to cover the whole screen — mapping the full frame would mean reaching
// far to one side just to touch the edge of the display, and never quite getting to the corners.
private const val POINTER_ACTIVE_LO = 0.2f
private const val POINTER_ACTIVE_HI = 0.8f

/** Low-pass factor for the pointer. Landmarks jitter by a few pixels every frame; unsmoothed the
 * dot visibly buzzes even with a perfectly still hand. */
private const val POINTER_SMOOTHING = 0.35f

/** How far the fingertip may drift, as a fraction of the screen, and still count as held still
 * for a tap. */
private const val POINTER_TAP_TOLERANCE_PX = 28f

// Head movement, as a fraction of the distance between the eyes — so the same real head movement
// works at any distance from the phone. Roughly a third of an eye-span of travel.
private const val HEAD_MOVE_THRESHOLD = 0.35f
private const val HEAD_COOLDOWN_MS = 400L

/** Foreground service that keeps the front camera running while other apps are on screen,
 * classifies the gesture in each frame on-device, and — once the same gesture has been seen
 * for a few consecutive frames (debounce, avoids misfires) and the cooldown has elapsed
 * (avoids repeat-firing) — dispatches the mapped action via [ActionDispatcher]. */
class GestureControlService : LifecycleService() {

    private lateinit var mappingStore: GestureMappingStore
    private lateinit var faceMappingStore: FaceMappingStore
    private lateinit var toggles: GestureToggleStore
    private lateinit var proximityMappingStore: ProximityMappingStore
    private lateinit var sensitivity: SensitivityStore
    private lateinit var templates: GestureTemplateStore
    private var proximityDetector: ProximityGestureDetector? = null
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

    private val trackpadTracker = DirectionalMotionTracker(TRACKPAD_MOVE_THRESHOLD)

    /** Derived from the sensitivity setting, alongside the trackers' thresholds. Volatile because
     * they are rewritten on the analysis thread and read from the MediaPipe callback threads. */
    @Volatile private var pinchThreshold = PINCH_DISTANCE_DELTA_THRESHOLD
    @Volatile private var stableFramesRequired = STABLE_FRAMES_REQUIRED
    private var appliedSensitivityLevel = -1
    private var lastTrackpadFiredAtMs = 0L
    private var trackpadTapped = false
    private var trackpadDropoutFrames = 0

    private val headTracker = DirectionalMotionTracker(HEAD_MOVE_THRESHOLD)
    private var lastHeadFiredAtMs = 0L

    private var pointerOverlay: PointerOverlay? = null
    private var pointerX = -1f
    private var pointerY = -1f
    private var pointerAnchorX = 0f
    private var pointerAnchorY = 0f
    private var pointerStillFrames = 0
    private var pointerTapped = false

    private var lastFaceFiredAtMs = 0L
    private var candidateFaceGesture: FaceGesture? = null
    private var candidateFaceStreak = 0

    override fun onCreate() {
        super.onCreate()
        mappingStore = GestureMappingStore(this)
        faceMappingStore = FaceMappingStore(this)
        toggles = GestureToggleStore(this)
        proximityMappingStore = ProximityMappingStore(this)
        sensitivity = SensitivityStore(this)
        templates = GestureTemplateStore(this)
        applySensitivity()
        startForeground(NOTIFICATION_ID, buildNotification())
        startProximityDetection()
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
        // Last, so the flag only ever means "set up and watching". If anything above throws, the
        // service never reports itself running and the tile stays off — which is the truth.
        setRunning(true)
    }

    /**
     * Wave detection runs on the proximity sensor, entirely independent of the camera.
     *
     * That independence is the point: it keeps working while the camera is released for the
     * screen being off, which is precisely the window the camera cannot cover. Waving to wake the
     * screen therefore still works with battery saving on — the trade-off that would otherwise
     * force a choice between the two.
     *
     * Not tied to screen state, and not restarted on resume, because a hardware-triggered sensor
     * costs a fraction of a milliamp; gating it would save nothing and add a failure mode.
     */
    private fun startProximityDetection() {
        val detector = ProximityGestureDetector(this) { gesture -> onProximityGesture(gesture) }
        if (!detector.isAvailable) {
            Log.i(TAG, "no proximity sensor; wave gestures disabled on this device")
            return
        }
        proximityDetector = detector
        if (toggles.waveEnabled) detector.start()
    }

    private fun onProximityGesture(gesture: ProximityGesture) {
        if (!toggles.waveEnabled) return
        val action = proximityMappingStore.load()[gesture] ?: return
        ActionDispatcher.fire(action)
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
        setRunning(false)
        runCatching { unregisterReceiver(screenStateReceiver) }
        proximityDetector?.stop()
        proximityDetector = null
        pointerOverlay?.destroy()
        pointerOverlay = null
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

    /**
     * Rescales every movement threshold and the pose debounce to the user's sensitivity setting.
     *
     * Called once per frame rather than only when the setting screen writes it, because the
     * service has no other notification that it changed and a setting that needs the service
     * restarted to take effect reads as broken. The early return makes the steady-state cost a
     * single volatile read and an int comparison.
     */
    private fun applySensitivity() {
        val level = sensitivity.level
        if (level == appliedSensitivityLevel) return
        appliedSensitivityLevel = level

        val scale = SensitivityStore.motionScaleFor(level)
        trackpadTracker.moveThreshold = TRACKPAD_MOVE_THRESHOLD * scale
        headTracker.moveThreshold = HEAD_MOVE_THRESHOLD * scale
        pinchThreshold = PINCH_DISTANCE_DELTA_THRESHOLD * scale
        stableFramesRequired = SensitivityStore.stableFramesFor(level)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        applySensitivity()
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

        val top = classifyHand(result)

        when (top) {
            // A single extended index finger drives the continuous mini-trackpad below instead
            // of a fixed discrete action, so it's kept out of the discrete debounce entirely.
            Gesture.POINT -> {
                candidateGesture = Gesture.UNKNOWN
                candidateStreak = 0
                lastPinchDistance = null
                trackpadDropoutFrames = 0
                handleFingerTrackpad(result)
            }
            Gesture.UNKNOWN -> {
                handleDetectedGesture(Gesture.UNKNOWN)
                handlePinchTracking(result)
                maybeResetTrackpad()
            }
            else -> {
                handleDetectedGesture(top)
                lastPinchDistance = null
                maybeResetTrackpad()
            }
        }
    }

    /**
     * Turns this frame's hand into a gesture, and fills a training request if one is running.
     *
     * The user's own recorded shapes are tried first and the rule-based classifier is the
     * fallback, not the other way round: where someone has demonstrated what *their* fist looks
     * like, that beats a rule tuned on somebody else's hand. [GestureTemplateStore.classify]
     * returns null whenever the match isn't clearly good, so a half-formed or unfamiliar pose
     * still gets the rules rather than a guess.
     *
     * Recording happens here, before classification, because a gesture being taught is by
     * definition one the current classifier gets wrong — waiting for it to be recognised first
     * would make the feature useless for exactly the hands that need it.
     */
    private fun classifyHand(
        result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult,
    ): Gesture {
        val points = result.firstHandPoints() ?: return Gesture.UNKNOWN

        TrainingSession.gestureToCapture()?.let { target ->
            if (templates.record(target, points)) TrainingSession.onCaptured()
        }

        return templates.classify(points) ?: classifyGesture(points, result.firstHandedness())
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

        // Pointer mode replaces swipe mode rather than running alongside it. Both at once would
        // mean the dot tracking your finger while the page scrolls under it, which reads as the
        // pointer causing the scroll.
        if (toggles.pointerEnabled) {
            handleAirPointer(tip.x(), tip.y())
            return
        }
        hidePointer()

        // Fed on every frame, including during the cooldown. That's the point: travel
        // accumulates against an anchor that only moves when something fires, so a movement made
        // while the cooldown was active still counts instead of being discarded.
        val direction = trackpadTracker.update(tip.x(), tip.y())

        if (direction == null) {
            // Hold still to tap. "Still" now means it hasn't travelled from the anchor, rather
            // than that two consecutive noisy frames happened to look similar.
            if (!trackpadTapped && trackpadTracker.stillUpdates >= TRACKPAD_HOLD_FRAMES_FOR_TAP) {
                trackpadTapped = true
                lastTrackpadFiredAtMs = SystemClock.elapsedRealtime()
                ActionDispatcher.fire(GestureAction(ActionType.TAP))
            }
            return
        }

        trackpadTapped = false
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrackpadFiredAtMs < TRACKPAD_COOLDOWN_MS) return

        lastTrackpadFiredAtMs = now
        ActionDispatcher.fire(GestureAction(direction.toSwipe()))
    }

    /**
     * Head movement as a gesture, tracked the same way as the finger trackpad: displacement of
     * the nose over time, normalised by the distance between the eyes so it behaves the same
     * close up and at arm's length.
     *
     * Firing here also puts the discrete face path on cooldown. Turning your head moves the eyes
     * through the frame too, and without that the same motion could land a head swipe and a gaze
     * swipe back to back.
     */
    private fun handleHeadMotion(result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult) {
        val head = result.headPoint()
        if (head == null) {
            headTracker.reset()
            return
        }

        val direction = headTracker.update(head.x, head.y, head.scale) ?: return

        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadFiredAtMs < HEAD_COOLDOWN_MS) return
        lastHeadFiredAtMs = now
        lastFaceFiredAtMs = now

        val gesture = when (direction) {
            Direction.LEFT -> FaceGesture.HEAD_LEFT
            Direction.RIGHT -> FaceGesture.HEAD_RIGHT
            Direction.UP -> FaceGesture.HEAD_UP
            Direction.DOWN -> FaceGesture.HEAD_DOWN
        }
        faceMappingStore.load()[gesture]?.let { ActionDispatcher.fire(it) }
    }

    private fun Direction.toSwipe(): ActionType = when (this) {
        Direction.LEFT -> ActionType.SWIPE_LEFT
        Direction.RIGHT -> ActionType.SWIPE_RIGHT
        Direction.UP -> ActionType.SWIPE_UP
        Direction.DOWN -> ActionType.SWIPE_DOWN
    }

    /**
     * Ends a trackpad stroke only after the pose has been gone for several frames.
     *
     * Resetting on the first non-POINT frame defeated the whole point of accumulating
     * displacement: pose classification flickers for a frame or two during a fast sweep — motion
     * blur, the hand tilting, part of it leaving the camera's view — and each flicker threw away
     * every bit of travel measured so far, so the threshold was never reached. Tolerating a brief
     * dropout is what lets a real movement survive being momentarily unrecognisable.
     */
    private fun maybeResetTrackpad() {
        trackpadDropoutFrames++
        if (trackpadDropoutFrames >= TRACKPAD_DROPOUT_GRACE_FRAMES) resetTrackpad()
    }

    private fun resetTrackpad() {
        trackpadTracker.reset()
        trackpadTapped = false
        hidePointer()
    }

    /**
     * Moves the on-screen dot to follow the fingertip, and taps where it sits when the finger
     * holds still.
     *
     * Tapping at the pointer rather than the screen centre is the substantive part: the dot makes
     * the target visible, so tapping anywhere else would contradict what the user can see.
     */
    private fun handleAirPointer(rawX: Float, rawY: Float) {
        val metrics = resources.displayMetrics
        val targetX = mapToScreen(rawX, metrics.widthPixels)
        val targetY = mapToScreen(rawY, metrics.heightPixels)

        if (pointerX < 0f) {
            pointerX = targetX
            pointerY = targetY
            pointerAnchorX = targetX
            pointerAnchorY = targetY
            pointerStillFrames = 0
            pointerTapped = false
        } else {
            pointerX += POINTER_SMOOTHING * (targetX - pointerX)
            pointerY += POINTER_SMOOTHING * (targetY - pointerY)
        }

        val overlay = pointerOverlay ?: PointerOverlay(this).also { pointerOverlay = it }
        overlay.moveTo(pointerX.toInt(), pointerY.toInt())

        val drift = kotlin.math.hypot(pointerX - pointerAnchorX, pointerY - pointerAnchorY)
        if (drift > POINTER_TAP_TOLERANCE_PX) {
            pointerAnchorX = pointerX
            pointerAnchorY = pointerY
            pointerStillFrames = 0
            pointerTapped = false
            return
        }

        pointerStillFrames++
        if (!pointerTapped && pointerStillFrames >= TRACKPAD_HOLD_FRAMES_FOR_TAP) {
            pointerTapped = true
            ActionDispatcher.tapAt(pointerX, pointerY)
        }
    }

    /** Stretches the comfortable middle band of the camera view across the full screen axis. */
    private fun mapToScreen(normalized: Float, sizePx: Int): Float {
        val t = ((normalized - POINTER_ACTIVE_LO) / (POINTER_ACTIVE_HI - POINTER_ACTIVE_LO))
            .coerceIn(0f, 1f)
        return t * sizePx
    }

    private fun hidePointer() {
        if (pointerX < 0f && pointerOverlay?.isShowing != true) return
        pointerX = -1f
        pointerY = -1f
        pointerStillFrames = 0
        pointerTapped = false
        pointerOverlay?.hide()
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
        if (kotlin.math.abs(delta) < pinchThreshold) return

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
        if (candidateStreak < stableFramesRequired) return

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

        // Head movement is a continuous motion rather than an expression, so it is tracked
        // separately and bypasses the blendshape debounce below.
        handleHeadMotion(result)

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
        if (candidateFaceStreak < stableFramesRequired) return

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

    /** Kept next to the flag so no caller can flip it without the tile being told. */
    private fun setRunning(running: Boolean) {
        isRunning = running
        GestureTileService.requestUpdate(this)
    }

    companion object {
        /**
         * Whether an instance is alive. Android offers no supported way to ask "is my service
         * running" — `getRunningServices` is deprecated and returns only this app's services on
         * modern releases — so the service reports it itself. The process hosts exactly one
         * instance, and if the process dies the flag dies with it, which is the right answer.
         *
         * Volatile because the Quick Settings tile reads it on the main thread while onCreate /
         * onDestroy may be running from a different one.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GestureControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureControlService::class.java))
        }
    }
}
