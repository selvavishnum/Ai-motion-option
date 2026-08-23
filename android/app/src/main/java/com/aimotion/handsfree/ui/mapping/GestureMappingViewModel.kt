package com.aimotion.handsfree.ui.mapping

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimotion.handsfree.face.FaceGesture
import com.aimotion.handsfree.face.FaceMappingStore
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.Gesture
import com.aimotion.handsfree.gesture.GestureAction
import com.aimotion.handsfree.gesture.GestureMappingStore
import com.aimotion.handsfree.gesture.MAPPABLE_GESTURES
import com.aimotion.handsfree.gesture.ProximityGesture
import com.aimotion.handsfree.gesture.ProximityMappingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HAND_EMOJI = mapOf(
    "open_palm" to "✋", "fist" to "✊", "thumbs_up" to "👍",
    "thumbs_down" to "👎", "peace" to "✌️",
)
private val FACE_EMOJI = mapOf(
    "blink" to "😉", "eyebrows_up" to "😲", "mouth_open" to "😮", "smile" to "😄",
    "wink_left" to "😉", "wink_right" to "😜", "eyebrows_down" to "🥺",
    "gaze_left" to "👈", "gaze_right" to "👉",
    "head_left" to "⬅️", "head_right" to "➡️", "head_up" to "⬆️", "head_down" to "⬇️",
)
private val WAVE_EMOJI = mapOf("wave_once" to "👋", "wave_twice" to "🙌")

private fun String.toTitleLabel() = replace('_', ' ').replaceFirstChar { it.uppercase() }

/** All actions a gesture can be mapped to. LAUNCH_APP is intentionally last — it needs a
 * follow-up "choose app" step, so it reads best as the final, more-involved option. */
val ALL_ACTION_OPTIONS: List<ActionType> = ActionType.entries.sortedBy { it == ActionType.LAUNCH_APP }

/** Backs the gesture-mapping screen. Owns loading/saving all three trigger stores — hand, face
 * and wave, and exposes a single [MappingUiState] so the UI never has to reconcile two
 * independent loading flows itself. Reads/writes run on [Dispatchers.IO] even though the
 * current SharedPreferences-backed stores are fast, so swapping in a slower persistence layer
 * (e.g. a remote-synced store) later needs no UI-layer changes. */
class GestureMappingViewModel(application: Application) : AndroidViewModel(application) {

    private val handStore = GestureMappingStore(application)
    private val faceStore = FaceMappingStore(application)
    private val waveStore = ProximityMappingStore(application)

    private var handMapping: MutableMap<Gesture, GestureAction> = mutableMapOf()
    private var faceMapping: MutableMap<FaceGesture, GestureAction> = mutableMapOf()
    private var waveMapping: MutableMap<ProximityGesture, GestureAction> = mutableMapOf()

    private val _uiState = MutableStateFlow<MappingUiState>(MappingUiState.Loading)
    val uiState: StateFlow<MappingUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = MappingUiState.Loading
            try {
                withContext(Dispatchers.IO) {
                    handMapping = handStore.load().toMutableMap()
                    faceMapping = faceStore.load().toMutableMap()
                    waveMapping = waveStore.load().toMutableMap()
                }
                publish()
            } catch (e: Exception) {
                _uiState.value = MappingUiState.Error(
                    e.message ?: "Something went wrong loading your gesture mappings."
                )
            }
        }
    }

    fun setHandAction(gesture: Gesture, action: GestureAction) {
        handMapping = handMapping.toMutableMap().apply { put(gesture, action) }
        publish()
        viewModelScope.launch(Dispatchers.IO) { handStore.save(handMapping) }
    }

    fun setFaceAction(gesture: FaceGesture, action: GestureAction) {
        faceMapping = faceMapping.toMutableMap().apply { put(gesture, action) }
        publish()
        viewModelScope.launch(Dispatchers.IO) { faceStore.save(faceMapping) }
    }

    fun setWaveAction(gesture: ProximityGesture, action: GestureAction) {
        waveMapping = waveMapping.toMutableMap().apply { put(gesture, action) }
        publish()
        viewModelScope.launch(Dispatchers.IO) { waveStore.save(waveMapping) }
    }

    private fun publish() {
        _uiState.value = MappingUiState.Content(
            handItems = handMapping.entries
                // POINT drives the continuous air trackpad rather than one fixed action, and
                // UNKNOWN is a classification fallback; MAPPABLE_GESTURES is the single list that
                // decides which triggers are remappable, so this screen defers to it instead of
                // keeping its own idea.
                .filter { it.key in MAPPABLE_GESTURES }
                .sortedBy { it.key.name }
                .map { (gesture, action) ->
                    MappingItem(
                        id = "hand_${gesture.name}",
                        emoji = HAND_EMOJI[gesture.label] ?: "🖐️",
                        label = gesture.label.toTitleLabel(),
                        action = action,
                    )
                },
            faceItems = faceMapping.entries
                .sortedBy { it.key.name }
                .map { (gesture, action) ->
                    MappingItem(
                        id = "face_${gesture.name}",
                        emoji = FACE_EMOJI[gesture.label] ?: "🙂",
                        label = gesture.label.toTitleLabel(),
                        action = action,
                    )
                },
            waveItems = waveMapping.entries
                .sortedBy { it.key.name }
                .map { (gesture, action) ->
                    MappingItem(
                        id = "wave_${gesture.name}",
                        emoji = WAVE_EMOJI[gesture.label] ?: "👋",
                        label = gesture.label.toTitleLabel(),
                        action = action,
                    )
                },
        )
    }

    /** Resolves the current [handMapping]/[faceMapping] entry a [MappingItem.id] came from, so
     * the UI can call [setHandAction]/[setFaceAction] without the components needing to know
     * about the underlying enum types. */
    fun applyAction(itemId: String, newAction: GestureAction) {
        if (itemId.startsWith("hand_")) {
            val name = itemId.removePrefix("hand_")
            Gesture.entries.firstOrNull { it.name == name }?.let { setHandAction(it, newAction) }
        } else if (itemId.startsWith("face_")) {
            val name = itemId.removePrefix("face_")
            FaceGesture.entries.firstOrNull { it.name == name }?.let { setFaceAction(it, newAction) }
        } else if (itemId.startsWith("wave_")) {
            val name = itemId.removePrefix("wave_")
            ProximityGesture.entries.firstOrNull { it.name == name }?.let { setWaveAction(it, newAction) }
        }
    }

    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo ->
                InstalledApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(pm).toString(),
                    icon = runCatching { resolveInfo.loadIcon(pm).toImageBitmapOrNull() }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}

private fun Drawable.toImageBitmapOrNull(): ImageBitmap? {
    val bitmap = (this as? BitmapDrawable)?.bitmap ?: run {
        val width = intrinsicWidth.coerceAtLeast(1)
        val height = intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bmp
    }
    return bitmap.asImageBitmap()
}
