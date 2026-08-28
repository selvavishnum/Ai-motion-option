package com.aimotion.handsfree.ui.training

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aimotion.handsfree.gesture.Gesture
import com.aimotion.handsfree.gesture.GestureControlService
import com.aimotion.handsfree.gesture.GestureTemplateStore
import com.aimotion.handsfree.gesture.MAPPABLE_GESTURES
import com.aimotion.handsfree.gesture.TrainingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One row: a gesture, how many examples exist, and whether that is enough to be used. */
data class TrainedGesture(
    val gesture: Gesture,
    val emoji: String,
    val label: String,
    val sampleCount: Int,
) {
    val isActive: Boolean get() = sampleCount >= GestureTemplateStore.MIN_SAMPLES
}

private val EMOJI = mapOf(
    "open_palm" to "✋", "fist" to "✊", "thumbs_up" to "👍",
    "thumbs_down" to "👎", "peace" to "✌️",
)

class GestureTrainingViewModel(application: Application) : AndroidViewModel(application) {

    private val store = GestureTemplateStore(application)

    private val _rows = MutableStateFlow(readRows())
    val rows: StateFlow<List<TrainedGesture>> = _rows.asStateFlow()

    val session = TrainingSession.state

    /** Recording is filled by the detection service, so without it running the screen can do
     * nothing but say so. */
    val serviceRunning: Boolean get() = GestureControlService.isRunning

    private fun readRows(): List<TrainedGesture> = MAPPABLE_GESTURES.map { gesture ->
        TrainedGesture(
            gesture = gesture,
            emoji = EMOJI[gesture.label] ?: "🖐️",
            label = gesture.label.replace('_', ' ').replaceFirstChar { it.uppercase() },
            sampleCount = store.sampleCount(gesture),
        )
    }

    fun refresh() {
        _rows.value = readRows()
    }

    fun startRecording(gesture: Gesture) {
        // Recording replaces rather than adds to what is there: someone re-teaching a gesture is
        // telling us the stored version is wrong, so averaging the new attempts together with it
        // would preserve exactly the shape they came here to correct.
        store.clear(gesture)
        refresh()
        TrainingSession.start(gesture, GestureTemplateStore.MIN_SAMPLES)
    }

    fun cancelRecording() {
        TrainingSession.cancel()
        refresh()
    }

    fun forget(gesture: Gesture) {
        store.clear(gesture)
        refresh()
    }

    fun forgetAll() {
        store.clearAll()
        refresh()
    }

    fun acknowledgeCompletion() {
        TrainingSession.acknowledgeCompletion()
        refresh()
    }
}
