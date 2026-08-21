package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.aimotion.handsfree.gesture.ActionType

/** An accessible, single-select dropdown for choosing an [ActionType] — the Compose-idiomatic,
 * TalkBack-friendly replacement for the legacy View-based [android.widget.Spinner]. Fully
 * stateless: the caller owns [selected] and receives [onSelected] callbacks, so it composes
 * cleanly with a ViewModel-driven screen (no hidden internal state to get out of sync). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionDropdown(
    selected: ActionType,
    options: List<ActionType>,
    onSelected: (ActionType) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Action",
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.semantics { contentDescription = "$label: ${selected.displayName()}" },
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            value = selected.displayName(),
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName()) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

fun ActionType.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Preview(showBackground = true, backgroundColor = 0xFF16171D)
@Composable
private fun ActionDropdownPreview() {
    MappingTheme {
        ActionDropdown(
            selected = ActionType.SWIPE_UP,
            options = ALL_ACTION_OPTIONS,
            onSelected = {},
        )
    }
}
