package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp

/**
 * A searchable, scroll-performant list of installed apps, presented as a Material3 bottom
 * sheet. Used to fill in the "which app" step of the Launch-app action.
 *
 * ## Props
 * - [apps]: full unfiltered list — filtering happens locally as the user types, so this stays
 *   a dumb, reusable "pick one of these" component with no knowledge of PackageManager.
 * - [onDismiss] / [onAppSelected]: standard hoisted-state callbacks; the sheet holds no
 *   business state itself, only the transient search-query text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onAppSelected: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
        }

        if (apps.isEmpty()) {
            EmptyState(
                emoji = "📦",
                title = "No apps found",
                body = "Couldn't find any launchable apps on this device.",
            )
        } else if (filtered.isEmpty()) {
            EmptyState(
                emoji = "🔍",
                title = "No matches",
                body = "No installed app matches \"$query\".",
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                items(items = filtered, key = { it.packageName }) { app ->
                    AppPickerRow(app = app, onClick = { onAppSelected(app) })
                }
            }
        }
    }
}

@Composable
private fun AppPickerRow(app: InstalledApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) {
            androidx.compose.foundation.Image(
                painter = BitmapPainter(app.icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray),
            )
        }
        Text(
            app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
