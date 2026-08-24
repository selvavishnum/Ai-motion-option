package com.aimotion.handsfree.ui.mapping

import androidx.compose.ui.graphics.ImageBitmap
import com.aimotion.handsfree.gesture.GestureAction

/** UI-layer view of one gesture->action mapping row. Deliberately decoupled from the domain
 * [com.aimotion.handsfree.gesture.Gesture] / [com.aimotion.handsfree.face.FaceGesture] enums so
 * the same Compose components render both hand and face triggers without knowing which one
 * they're looking at. */
data class MappingItem(
    val id: String,
    val emoji: String,
    val label: String,
    val action: GestureAction,
)

/** One installed, launchable app — used by [AppPickerSheet]. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** UI state for the whole gesture-mapping screen: exactly one of these is true at a time,
 * making impossible states (e.g. "loading" + "has data" simultaneously) unrepresentable. */
sealed interface MappingUiState {
    data object Loading : MappingUiState
    data class Error(val message: String) : MappingUiState
    data class Content(
        val handItems: List<MappingItem>,
        val faceItems: List<MappingItem>,
        val waveItems: List<MappingItem>,
    ) : MappingUiState {
        val isEmpty: Boolean
            get() = handItems.isEmpty() && faceItems.isEmpty() && waveItems.isEmpty()
    }
}
