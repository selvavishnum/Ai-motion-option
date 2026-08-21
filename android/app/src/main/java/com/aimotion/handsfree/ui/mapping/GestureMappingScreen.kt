package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aimotion.handsfree.gesture.ActionType
import com.aimotion.handsfree.gesture.GestureAction
import kotlinx.coroutines.launch

/**
 * The full gesture-mapping screen: loading/empty/error handling, then two sections (hand and
 * face gestures) sharing one scrollable list, plus the app picker sheet for Launch-app actions.
 *
 * This composable owns no business logic itself — it renders whatever [GestureMappingViewModel]
 * publishes and forwards user actions back to it, so it (and every component it's built from)
 * stays trivially unit-testable / previewable without a real ViewModel or Android framework.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureMappingScreen(
    onBack: () -> Unit,
    viewModel: GestureMappingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var appPickerTargetId by remember { mutableStateOf<String?>(null) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(appPickerTargetId) {
        if (appPickerTargetId != null && installedApps.isEmpty()) {
            installedApps = viewModel.loadInstalledApps()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gesture mapping") },
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
        when (val state = uiState) {
            is MappingUiState.Loading -> LoadingState(
                label = "Loading gesture mappings",
                modifier = Modifier.padding(padding),
            )

            is MappingUiState.Error -> ErrorState(
                message = state.message,
                onRetry = viewModel::load,
                modifier = Modifier.padding(padding),
            )

            is MappingUiState.Content -> if (state.isEmpty) {
                EmptyState(
                    emoji = "🤷",
                    title = "No gestures available",
                    body = "Gesture definitions are currently empty. Try updating the app.",
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    mappingSection(
                        title = "Hand gestures",
                        subtitle = "Tap an action to change it, or Choose app for Launch app.",
                        items = state.handItems,
                        onActionSelected = { item, action ->
                            viewModel.applyAction(item.id, resolveNewAction(item.action, action))
                        },
                        onChooseApp = { item -> appPickerTargetId = item.id },
                    )
                    mappingSection(
                        title = "Face gestures",
                        subtitle = "Blink, raised eyebrows, open mouth, or a smile.",
                        items = state.faceItems,
                        onActionSelected = { item, action ->
                            viewModel.applyAction(item.id, resolveNewAction(item.action, action))
                        },
                        onChooseApp = { item -> appPickerTargetId = item.id },
                    )
                }
            }
        }

        val targetId = appPickerTargetId
        if (targetId != null) {
            AppPickerSheet(
                apps = installedApps,
                onDismiss = { appPickerTargetId = null },
                onAppSelected = { app ->
                    scope.launch {
                        viewModel.applyAction(targetId, GestureAction(ActionType.LAUNCH_APP, app.packageName))
                        appPickerTargetId = null
                    }
                },
            )
        }
    }
}

/** Picking a new [ActionType] from the dropdown drops any previously-chosen app package (a
 * fresh Launch-app selection should re-prompt "which app", not silently keep a stale one from
 * a different gesture). */
private fun resolveNewAction(current: GestureAction, newType: ActionType): GestureAction =
    if (newType == current.type) current else GestureAction(newType, packageName = null)
