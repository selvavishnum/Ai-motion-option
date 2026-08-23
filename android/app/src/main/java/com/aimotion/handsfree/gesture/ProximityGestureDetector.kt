package com.aimotion.handsfree.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val TAG = "ProximityDetector"

/** How long after the last wave to wait before deciding how many there were. Long enough to
 * catch a deliberate double wave, short enough not to feel laggy on a single one. */
private const val WAVE_WINDOW_MS = 700L

/** A hand passing over the sensor covers it briefly. Anything longer is the phone going into a
 * pocket, lying face down, or being held to an ear — none of which are gestures, and all of
 * which would otherwise fire constantly. This guard is what makes the mode usable at all. */
private const val MAX_NEAR_MS = 1_200L

/** Below this it's sensor noise rather than a hand. */
private const val MIN_NEAR_MS = 60L

/**
 * Wave gestures detected with the proximity sensor — the same hardware that blanks the screen
 * during a call.
 *
 * Deliberately tiny compared to the camera vocabulary, because the sensor genuinely cannot do
 * more: it reports one number, "something is near" or "nothing is near". There is no shape, no
 * direction, no distance worth trusting. Counting waves is the entire signal available.
 *
 * What it buys in exchange is power. The proximity sensor is hardware-triggered and costs a
 * fraction of a milliamp against the camera's continuous capture-plus-inference, and it keeps
 * working while the screen is off — which is exactly the window where the camera is released to
 * save battery, and therefore where nothing else is watching.
 */
enum class ProximityGesture(val label: String) {
    WAVE_ONCE("wave_once"),
    WAVE_TWICE("wave_twice"),
}

/** Wave once to wake the screen — the case the camera cannot cover, since it is unbound while
 * the screen is off. Every entry is remappable in Settings. */
val DEFAULT_PROXIMITY_MAPPING: Map<ProximityGesture, GestureAction> = mapOf(
    ProximityGesture.WAVE_ONCE to GestureAction(ActionType.WAKE_SCREEN),
    ProximityGesture.WAVE_TWICE to GestureAction(ActionType.HOME),
)

/**
 * Turns proximity near/far transitions into wave gestures.
 *
 * A wave is a far → near → far cycle whose "near" phase lasted a plausible amount of time. Waves
 * inside [WAVE_WINDOW_MS] of each other are counted together, so one deliberate double wave
 * emits [ProximityGesture.WAVE_TWICE] rather than two singles.
 */
class ProximityGestureDetector(
    context: Context,
    private val onGesture: (ProximityGesture) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    /** Not every device has this sensor; callers should degrade quietly rather than assume. */
    val isAvailable: Boolean get() = sensor != null

    private val handler = Handler(Looper.getMainLooper())

    private var isNear = false
    private var nearSinceMs = 0L
    private var waveCount = 0
    private var listening = false

    private val emitPending = Runnable {
        val count = waveCount
        waveCount = 0
        when {
            count <= 0 -> Unit
            count == 1 -> onGesture(ProximityGesture.WAVE_ONCE)
            // Three or more is almost certainly an enthusiastic double rather than a distinct
            // gesture, so it collapses instead of being ignored.
            else -> onGesture(ProximityGesture.WAVE_TWICE)
        }
    }

    fun start() {
        val s = sensor
        if (s == null) {
            Log.i(TAG, "no proximity sensor on this device; wave gestures unavailable")
            return
        }
        if (listening) return
        // SENSOR_DELAY_NORMAL is ample: a hand wave lasts hundreds of milliseconds, and a faster
        // rate would only cost power in the one mode whose whole point is not costing power.
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        listening = true
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(emitPending)
        listening = false
        isNear = false
        waveCount = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val maxRange = sensor?.maximumRange ?: return
        // Most proximity sensors are effectively binary: 0 when covered, maximumRange when not.
        // Comparing against the range rather than a fixed centimetre value keeps this correct on
        // devices that report an actual distance.
        val nearNow = event.values.isNotEmpty() && event.values[0] < maxRange
        val now = SystemClock.elapsedRealtime()

        if (nearNow == isNear) return
        isNear = nearNow

        if (nearNow) {
            nearSinceMs = now
            return
        }

        val coveredForMs = now - nearSinceMs
        if (coveredForMs !in MIN_NEAR_MS..MAX_NEAR_MS) return // noise, or a pocket

        waveCount++
        handler.removeCallbacks(emitPending)
        handler.postDelayed(emitPending, WAVE_WINDOW_MS)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
