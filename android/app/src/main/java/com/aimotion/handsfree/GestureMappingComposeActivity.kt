package com.aimotion.handsfree

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aimotion.handsfree.ui.mapping.GestureMappingScreen
import com.aimotion.handsfree.ui.mapping.MappingTheme

/**
 * Hosts the Compose-based [GestureMappingScreen] — the production-grade rewrite of the
 * gesture-mapping settings UI, built as reusable components under `ui/mapping/`.
 *
 * Kept as a separate entry point from [MainActivity] (which still uses the original
 * View/RecyclerView-based mapping list) rather than replacing it outright: the existing screen
 * is already verified working end-to-end on a real device, and this Compose rewrite hasn't had
 * that same on-device verification yet. Once confirmed, `MainActivity` can link here directly
 * and the old View-based mapping UI can be retired.
 */
class GestureMappingComposeActivity : ComponentActivity() {
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
