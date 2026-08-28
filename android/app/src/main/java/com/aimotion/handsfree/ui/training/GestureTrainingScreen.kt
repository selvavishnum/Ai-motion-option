package com.aimotion.handsfree.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aimotion.handsfree.gesture.GestureTemplateStore

/**
 * Teach the app your own hand shapes.
 *
 * Deliberately shows no camera preview. The service owns the camera, and opening a second one
 * here would stop gesture control while the user records the gestures gesture control is meant to
 * use. What the user needs to know is whether the recording is progressing, which the counter
 * shows honestly: it only advances when a hand was actually seen and stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureTrainingScreen(
    onBack: () -> Unit,
    viewModel: GestureTrainingViewModel = viewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(session.justCompleted) {
        if (session.justCompleted != null) viewModel.acknowledgeCompletion()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teach your gestures") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Hold a gesture the way you naturally make it and the app records a few " +
                        "examples. After that it matches your hand instead of a built-in rule — " +
                        "which is the point if a gesture keeps being missed or your fingers " +
                        "don't move the way the default expects.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            if (!viewModel.serviceRunning) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Gesture control is off. Recording uses the camera the detection " +
                                "service already has, so turn it on — from the main screen or " +
                                "the Quick Settings tile — then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            if (session.isRecording) {
                item {
                    RecordingCard(
                        label = rows.firstOrNull { it.gesture == session.target }?.label.orEmpty(),
                        captured = session.captured,
                        total = GestureTemplateStore.MIN_SAMPLES,
                        onCancel = viewModel::cancelRecording,
                    )
                }
            }

            items(rows, key = { it.gesture.name }) { row ->
                GestureRow(
                    row = row,
                    enabled = viewModel.serviceRunning && !session.isRecording,
                    onTeach = { viewModel.startRecording(row.gesture) },
                    onForget = { viewModel.forget(row.gesture) },
                )
            }

            if (rows.any { it.sampleCount > 0 }) {
                item {
                    TextButton(
                        onClick = viewModel::forgetAll,
                        enabled = !session.isRecording,
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        Text("Forget everything and go back to the built-in rules")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(
    label: String,
    captured: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Hold: $label", style = MaterialTheme.typography.titleMedium)
            Text(
                "Keep the gesture in front of the camera. Move your hand a little between " +
                    "captures — slightly turned, slightly closer — so the app learns the range " +
                    "you actually hold it in, not one frozen instant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else captured.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                "$captured of $total captured",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun GestureRow(
    row: TrainedGesture,
    enabled: Boolean,
    onTeach: () -> Unit,
    onForget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: a screen reader should announce "Open palm, learned", not "hand with
            // palm facing up emoji, Open palm, learned".
            Text(
                row.emoji,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (row.isActive) "Learned — your hand is being matched" else "Using the built-in rule",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.isActive) {
                OutlinedButton(onClick = onForget, enabled = enabled) { Text("Forget") }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onTeach, enabled = enabled) {
                Text(if (row.isActive) "Redo" else "Teach")
            }
        }
    }
}
