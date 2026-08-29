package com.aimotion.handsfree

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aimotion.handsfree.ui.mapping.MappingTheme
import com.aimotion.handsfree.ui.training.GestureTrainingScreen

/**
 * Teaches the app the user's own hand shapes.
 *
 * Requires gesture control to be running: the recording is filled by the detection service, which
 * already owns the camera. See [com.aimotion.handsfree.gesture.TrainingSession].
 */
class GestureTrainingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MappingTheme {
                GestureTrainingScreen(onBack = { finish() })
            }
        }
    }
}
