package com.aimotion.handsfree

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.aimotion.handsfree.ui.paper.PaperCard
import com.aimotion.handsfree.ui.paper.PaperEmptyState
import com.aimotion.handsfree.ui.paper.PaperRow
import com.aimotion.handsfree.ui.paper.PaperRule
import com.aimotion.handsfree.ui.paper.PaperScreen
import com.aimotion.handsfree.ui.paper.PaperSectionHeader
import com.aimotion.handsfree.ui.paper.PaperStatusPill
import com.aimotion.handsfree.ui.paper.PaperTheme
import com.aimotion.handsfree.ui.paper.PaperTone

/**
 * Lists every sensor the phone actually reports, with what Air Sensor can do with each.
 *
 * Exists because "which sensors does this model have?" cannot be answered reliably from a spec
 * sheet — variants differ by region, and marketing pages omit and invent things. The device
 * knows, so the device answers. It also shows each sensor's rated power draw, which is the
 * number that decides whether a sensor is a viable alternative to the camera.
 */
class SensorListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PaperTheme { SensorListScreen() } }
    }
}

/** What this app can do with a given sensor, and how confident that claim is. */
private data class SensorUse(val label: String, val tone: PaperTone, val note: String)

private fun useFor(type: Int): SensorUse = when (type) {
    Sensor.TYPE_PROXIMITY -> SensorUse(
        "In use", PaperTone.Positive,
        "Drives wave gestures. Detects a hand passing over the phone — near or far only, no shape.",
    )
    Sensor.TYPE_ACCELEROMETER -> SensorUse(
        "Possible", PaperTone.Neutral,
        "Could detect shake, flip or tilt. That is phone movement, not hand movement — not implemented.",
    )
    Sensor.TYPE_GYROSCOPE -> SensorUse(
        "Possible", PaperTone.Neutral,
        "Could detect rotation and tilt of the phone itself. Not implemented.",
    )
    Sensor.TYPE_LIGHT -> SensorUse(
        "Unreliable", PaperTone.Neutral,
        "A hand casting a shadow changes this, but so does walking into another room.",
    )
    else -> SensorUse(
        "Not usable", PaperTone.Critical,
        "Measures something unrelated to hand position.",
    )
}

/** A readable name for the well-known types; the raw string type otherwise, since OEMs ship
 * plenty of vendor-specific sensors with no public constant. */
private fun typeName(sensor: Sensor): String = when (sensor.type) {
    Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
    Sensor.TYPE_GYROSCOPE -> "Gyroscope"
    Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer (compass)"
    Sensor.TYPE_PROXIMITY -> "Proximity"
    Sensor.TYPE_LIGHT -> "Ambient light"
    Sensor.TYPE_PRESSURE -> "Barometer"
    Sensor.TYPE_GRAVITY -> "Gravity"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
    Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
    Sensor.TYPE_STEP_COUNTER -> "Step counter"
    Sensor.TYPE_STEP_DETECTOR -> "Step detector"
    Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
    Sensor.TYPE_HEART_RATE -> "Heart rate"
    else -> sensor.stringType ?: "Type ${sensor.type}"
}

@Composable
fun SensorListScreen() {
    val context = LocalContext.current
    val sensors = remember(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager.getSensorList(Sensor.TYPE_ALL)
            // Gesture-capable sensors first, then by name, so the useful ones are not buried
            // under a long tail of vendor-specific entries.
            .sortedWith(compareBy({ useFor(it.type).tone != PaperTone.Positive }, { typeName(it) }))
    }

    PaperScreen {
        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        Text(
            text = "Sensors on this phone",
            style = MaterialTheme.typography.headlineMedium,
            color = PaperTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Read from the device itself, not a spec sheet. Only the camera can see hand " +
                "shapes — everything here is far more limited.",
            style = MaterialTheme.typography.bodyMedium,
            color = PaperTheme.colors.inkMuted,
        )

        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        if (sensors.isEmpty()) {
            PaperEmptyState(
                title = "No sensors reported",
                body = "This device exposes no sensors to apps, which is unusual. Wave gestures " +
                    "will not work here.",
            )
        } else {
            PaperSectionHeader(
                "${sensors.size} sensors",
                subtitle = "Power is the manufacturer's rating while the sensor is active.",
            )
            Spacer(Modifier.height(PaperTheme.spacing.lg))
            PaperCard {
                sensors.forEachIndexed { index, sensor ->
                    if (index > 0) PaperRule()
                    val use = useFor(sensor.type)
                    PaperRow(
                        title = typeName(sensor),
                        subtitle = buildString {
                            append(use.note)
                            append("\n")
                            append(sensor.name)
                            append(" · ")
                            append("%.2f mA".format(sensor.power))
                        },
                        trailing = { PaperStatusPill(use.label, use.tone) },
                    )
                }
            }
        }

        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        PaperSectionHeader(
            "Why so few are usable",
            subtitle = "Most sensors measure the phone, not you.",
        )
        Spacer(Modifier.height(PaperTheme.spacing.lg))
        PaperCard {
            Text(
                text = "An accelerometer knows the phone moved. A magnetometer knows which way " +
                    "is north. Neither has any way to tell an open palm from a fist, or a " +
                    "finger moving left from one moving right.\n\n" +
                    "Proximity comes closest — it knows something is in front of the phone — " +
                    "but reports one bit, not a shape. That is why it can only count waves, " +
                    "and why every richer gesture needs the camera.",
                style = MaterialTheme.typography.bodyMedium,
                color = PaperTheme.colors.inkMuted,
            )
        }

        Spacer(Modifier.height(PaperTheme.spacing.xxxl))
    }
}
