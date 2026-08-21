package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reusable full-bleed states for any list/detail screen, not just gesture mapping — kept
 * generic (plain strings in, no gesture-domain knowledge) so other screens can reuse them. */

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 40.sp, modifier = Modifier.clearAndSetSemantics {})
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // A live region so screen readers announce the failure as soon as it appears, without
        // the user needing to swipe-navigate to find it.
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("⚠️", fontSize = 40.sp, modifier = Modifier.clearAndSetSemantics {})
        Text(
            "Couldn't load gesture mappings",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun LoadingStatePreview() {
    MappingTheme { LoadingState(label = "Loading gesture mappings") }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun EmptyStatePreview() {
    MappingTheme {
        EmptyState(
            emoji = "🤷",
            title = "No gestures available",
            body = "Gesture definitions are currently empty. Try updating the app.",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun ErrorStatePreview() {
    MappingTheme {
        ErrorState(message = "Storage is unavailable right now.", onRetry = {})
    }
}
