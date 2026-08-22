package com.aimotion.handsfree

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aimotion.handsfree.databinding.ActivityGestureGuideBinding
import com.aimotion.handsfree.databinding.ItemGestureGuideBinding
import com.aimotion.handsfree.face.DEFAULT_FACE_MAPPING
import com.aimotion.handsfree.gesture.DEFAULT_MAPPING

private data class GuideEntry(val emoji: String, val title: String, val howTo: String)

private val HAND_HOW_TO = mapOf(
    "open_palm" to "Hold your open hand up, fingers spread, palm facing the camera. Wakes the screen.",
    "fist" to "Curl all fingers and thumb into a closed fist. Closing your palm goes home.",
    "thumbs_up" to "Curl your fingers, extend only your thumb pointing up.",
    "thumbs_down" to "Curl your fingers, extend only your thumb pointing down.",
    "peace" to "Extend index and middle finger in a V, curl the rest.",
)

private val HAND_EMOJI = mapOf(
    "open_palm" to "✋", "fist" to "✊", "thumbs_up" to "👍",
    "thumbs_down" to "👎", "peace" to "✌️",
)

private val FACE_HOW_TO = mapOf(
    "blink" to "Close both eyes briefly, then open them.",
    "eyebrows_up" to "Raise both eyebrows, like a surprised look.",
    "eyebrows_down" to "Lower/furrow both eyebrows.",
    "mouth_open" to "Open your mouth/jaw noticeably, like a yawn.",
    "smile" to "Smile with both corners of your mouth.",
    "wink_left" to "Close only your left eye briefly, keep the right eye open.",
    "wink_right" to "Close only your right eye briefly, keep the left eye open.",
    "gaze_left" to "Keep your head still, look left with your eyes.",
    "gaze_right" to "Keep your head still, look right with your eyes.",
)

private val FACE_EMOJI = mapOf(
    "blink" to "😉", "eyebrows_up" to "😲", "mouth_open" to "😮", "smile" to "😄",
    "wink_left" to "😉", "wink_right" to "😜", "eyebrows_down" to "🥺",
    "gaze_left" to "👈", "gaze_right" to "👉",
)

class GestureGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGestureGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val handEntries = DEFAULT_MAPPING.entries
            .filter { it.key.label != "unknown" }
            .map { (gesture, action) ->
                GuideEntry(
                    emoji = HAND_EMOJI[gesture.label] ?: "🖐️",
                    title = "${gesture.label.replace('_', ' ').replaceFirstChar { it.uppercase() }} → ${action.describe()}",
                    howTo = HAND_HOW_TO[gesture.label] ?: "",
                )
            } + listOf(
                GuideEntry(
                    emoji = "☝️",
                    title = "Point → Air trackpad",
                    howTo = "Extend only your index finger. Move it to turn/scroll, hold it still to tap/select.",
                ),
                GuideEntry(
                    emoji = "🤏",
                    title = "Two fingers → Pinch zoom",
                    howTo = "Extend index and middle finger, then move them apart or together to zoom in/out.",
                ),
            )
        binding.handGuideList.layoutManager = LinearLayoutManager(this)
        binding.handGuideList.adapter = GuideAdapter(handEntries)

        val faceEntries = DEFAULT_FACE_MAPPING.entries.map { (gesture, action) ->
            GuideEntry(
                emoji = FACE_EMOJI[gesture.label] ?: "🙂",
                title = "${gesture.label.replace('_', ' ').replaceFirstChar { it.uppercase() }} → ${action.describe()}",
                howTo = FACE_HOW_TO[gesture.label] ?: "",
            )
        }
        binding.faceGuideList.layoutManager = LinearLayoutManager(this)
        binding.faceGuideList.adapter = GuideAdapter(faceEntries)
    }
}

private class GuideAdapter(private val entries: List<GuideEntry>) :
    RecyclerView.Adapter<GuideAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemGestureGuideBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGestureGuideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.binding.emoji.text = entry.emoji
        holder.binding.title.text = entry.title
        holder.binding.howTo.text = entry.howTo
    }
}
