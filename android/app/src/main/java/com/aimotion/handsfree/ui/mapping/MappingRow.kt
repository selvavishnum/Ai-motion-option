package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.GestureAction

/**
 * One gesture -> action mapping row: emoji + label, an [ActionDropdown] to change the action,
 * and (only for [ActionType.LAUNCH_APP]) a button to pick which app.
 *
 * ## Props
 * - [item]: what to render — trigger label/emoji and its current action. Purely data-driven.
 * - [actionOptions]: the full list of choices to offer (usually [ALL_ACTION_OPTIONS]) — passed
 *   in rather than hardcoded so a future screen could offer a restricted subset.
 * - [onActionSelected]: fired with the *new* [ActionType] the user picked. The row does not
 *   mutate [item] itself — state hoisting means the caller (ViewModel-backed screen) owns the
 *   source of truth and re-renders with the updated [item].
 * - [onChooseApp]: fired when the user taps "Choose app" — the row has no app-picker UI of its
 *   own; that's a separate, reusable [AppPickerSheet] the screen opens.
 */
@Composable
fun MappingRow(
    item: MappingItem,
    actionOptions: List<ActionType>,
    onActionSelected: (ActionType) -> Unit,
    onChooseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.emoji, fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics {})
                Spacer(Modifier.width(10.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            ActionDropdown(
                selected = item.action.type,
                options = actionOptions,
                onSelected = onActionSelected,
            )
            if (item.action.type == ActionType.LAUNCH_APP) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onChooseApp, modifier = Modifier.fillMaxWidth()) {
                    Text(item.action.packageName?.let { "App: $it" } ?: "Choose app…")
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun MappingRowPreview() {
    MappingTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MappingRow(
                item = MappingItem("hand_OPEN_PALM", "✋", "Open palm", GestureAction(ActionType.BACK)),
                actionOptions = ALL_ACTION_OPTIONS,
                onActionSelected = {},
                onChooseApp = {},
            )
            MappingRow(
                item = MappingItem(
                    "hand_FIST", "✊", "Fist",
                    GestureAction(ActionType.LAUNCH_APP, "com.instagram.android"),
                ),
                actionOptions = ALL_ACTION_OPTIONS,
                onActionSelected = {},
                onChooseApp = {},
            )
        }
    }
}
