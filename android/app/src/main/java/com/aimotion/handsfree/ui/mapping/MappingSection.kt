package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aimotion.handsfree.gesture.ActionType

/**
 * Adds one titled section of [MappingRow]s to a [LazyListScope] — a `LazyColumn` extension
 * rather than its own composable, so the hand- and face-gesture sections of
 * [GestureMappingScreen] share one scroll container (one nested-scrolling list, not two,
 * which is both a performance win and avoids the "scrollable inside scrollable" a11y trap).
 */
fun LazyListScope.mappingSection(
    title: String,
    subtitle: String?,
    items: List<MappingItem>,
    actionOptions: List<ActionType> = ALL_ACTION_OPTIONS,
    onActionSelected: (MappingItem, ActionType) -> Unit,
    onChooseApp: (MappingItem) -> Unit,
) {
    item(key = "${title}_header") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
    items(items = items, key = { it.id }) { item ->
        MappingRow(
            item = item,
            actionOptions = actionOptions,
            onActionSelected = { onActionSelected(item, it) },
            onChooseApp = { onChooseApp(item) },
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
