package com.aimotion.handsfree

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aimotion.handsfree.ui.mapping.GestureMappingScreen
import com.aimotion.handsfree.ui.mapping.MappingTheme

/**
 * The one place gestures are remapped — hand, face and wave triggers together.
 *
 * There used to be two: this Compose screen and a second, older View/RecyclerView table embedded
 * in MainActivity. Two implementations of the same settings meant two places to fix a bug and two
 * chances for them to disagree about what the user had chosen, so the older one is gone and this
 * screen absorbed the wave mappings it uniquely covered.
 */
class GestureMappingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MappingTheme {
                GestureMappingScreen(onBack = { finish() })
            }
        }
    }
}
