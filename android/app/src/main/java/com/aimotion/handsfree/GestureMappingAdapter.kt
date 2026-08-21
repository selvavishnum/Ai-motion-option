package com.aimotion.handsfree

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.recyclerview.widget.RecyclerView
import com.aimotion.handsfree.databinding.ItemGestureMappingBinding
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.GestureAction

/** Generic gesture->action mapping list, shared by the hand-gesture and face-gesture sections —
 * both need the same "pick an action, optionally choose an app" row, just keyed on a different
 * trigger type ([Gesture] or [FaceGesture]). */
class GestureMappingAdapter<T : Any>(
    private val triggers: List<T>,
    private val labelOf: (T) -> String,
    private val mapping: MutableMap<T, GestureAction>,
    private val onChanged: (T, GestureAction) -> Unit,
    private val onChooseApp: (T) -> Unit,
) : RecyclerView.Adapter<GestureMappingAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemGestureMappingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGestureMappingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = triggers.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val trigger = triggers[position]
        val action = mapping.getValue(trigger)
        val binding = holder.binding

        binding.gestureName.text = labelOf(trigger).replace('_', ' ').replaceFirstChar { it.uppercase() }

        val actionTypes = ActionType.entries.toList()
        binding.actionSpinner.adapter = ArrayAdapter(
            binding.root.context,
            android.R.layout.simple_spinner_dropdown_item,
            actionTypes.map { it.name.lowercase().replace('_', ' ') },
        )
        binding.actionSpinner.setSelection(actionTypes.indexOf(action.type))
        binding.chooseAppButton.visibility = if (action.type == ActionType.LAUNCH_APP) View.VISIBLE else View.GONE
        binding.chooseAppButton.text = action.packageName?.let { "App: $it" } ?: "Choose app…"

        binding.actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newType = actionTypes[pos]
                if (newType == action.type) return
                val newAction = GestureAction(newType, packageName = null)
                mapping[trigger] = newAction
                onChanged(trigger, newAction)
                notifyItemChanged(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.chooseAppButton.setOnClickListener { onChooseApp(trigger) }
    }
}
