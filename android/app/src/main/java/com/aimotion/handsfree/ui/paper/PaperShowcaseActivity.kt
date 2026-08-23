package com.aimotion.handsfree.ui.paper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A live gallery of the Paper design system.
 *
 * Exists as a real, shipped screen rather than a README snippet because a design system that can
 * only be reviewed by reading source drifts: every component's states are visible here, on a
 * real device, at the reader's real font scale and theme. It is also the fastest way to catch a
 * contrast or touch-target regression — you look at it.
 */
class PaperShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PaperTheme { PaperShowcaseScreen() } }
    }
}

/** Which state the demo block is currently rendering. */
private enum class DemoState { Content, Loading, Empty, Error }

@Composable
fun PaperShowcaseScreen() {
    var demoState by remember { mutableStateOf(DemoState.Content) }
    var airGestures by remember { mutableStateOf(true) }
    var faceGestures by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    PaperScreen {
        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        Text(
            text = "Paper",
            style = MaterialTheme.typography.headlineMedium,
            color = PaperTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "A minimalist component system. Every state below is real and interactive.",
            style = MaterialTheme.typography.bodyMedium,
            color = PaperTheme.colors.inkMuted,
        )

        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        // ---- Buttons -------------------------------------------------------------------
        PaperSectionHeader("Buttons", "One primary per screen. Weight signals priority.")
        Spacer(Modifier.height(PaperTheme.spacing.lg))
        PaperCard {
            Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
                PaperButton(
                    text = if (saving) "Saving" else "Save changes",
                    onClick = { saving = !saving },
                    loading = saving,
                    loadingLabel = "Saving changes",
                    fillWidth = true,
                )
                PaperButton("Secondary", {}, variant = PaperButtonVariant.Secondary, fillWidth = true)
                Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
                    PaperButton("Ghost", {}, variant = PaperButtonVariant.Ghost, size = PaperButtonSize.Small)
                    PaperButton("Delete", {}, variant = PaperButtonVariant.Danger, size = PaperButtonSize.Small)
                }
                PaperButton("Disabled", {}, enabled = false, fillWidth = true)
            }
        }

        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        // ---- Rows ----------------------------------------------------------------------
        PaperSectionHeader("Rows", "The whole row is the target, not just the switch.")
        Spacer(Modifier.height(PaperTheme.spacing.lg))
        PaperCard(contentPadding = PaperTheme.spacing.lg) {
            PaperSwitchRow(
                title = "Air gestures",
                checked = airGestures,
                onCheckedChange = { airGestures = it },
                description = "Hand poses control the screen.",
            )
            PaperRule()
            PaperSwitchRow(
                title = "Face gestures",
                checked = faceGestures,
                onCheckedChange = { faceGestures = it },
                description = "Blink and eyebrow triggers.",
            )
            PaperRule()
            PaperSwitchRow(
                title = "Unavailable setting",
                checked = false,
                onCheckedChange = {},
                description = "Disabled until a permission is granted.",
                enabled = false,
            )
            PaperRule()
            PaperRow(
                title = "Open palm",
                subtitle = "Wake screen",
                leading = "✋",
                onClick = {},
                trailing = { PaperStatusPill("Active", PaperTone.Positive) },
            )
            PaperRule()
            PaperRow(
                title = "Accessibility",
                subtitle = "Required to control other apps",
                trailing = { PaperStatusPill("Off", PaperTone.Critical) },
            )
        }

        Spacer(Modifier.height(PaperTheme.spacing.xxl))

        // ---- States --------------------------------------------------------------------
        PaperSectionHeader("States", "Tap to switch. Loading, empty and error are first-class.")
        Spacer(Modifier.height(PaperTheme.spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
            DemoState.entries.forEach { state ->
                PaperButton(
                    text = state.name,
                    onClick = { demoState = state },
                    variant = if (demoState == state) PaperButtonVariant.Primary
                    else PaperButtonVariant.Secondary,
                    size = PaperButtonSize.Small,
                )
            }
        }
        Spacer(Modifier.height(PaperTheme.spacing.lg))
        PaperCard {
            when (demoState) {
                DemoState.Content -> Column {
                    PaperRow(title = "Peace sign", subtitle = "Swipe right", leading = "✌️")
                    PaperRule()
                    PaperRow(title = "Thumbs up", subtitle = "Scroll up", leading = "👍")
                }
                DemoState.Loading -> PaperLoadingState(label = "Loading gestures", rows = 2)
                DemoState.Empty -> PaperEmptyState(
                    title = "No gestures configured",
                    body = "Add one to start controlling your phone hands-free.",
                    action = {
                        PaperButton("Add gesture", {}, size = PaperButtonSize.Small)
                    },
                )
                DemoState.Error -> PaperErrorState(
                    title = "Couldn't load your settings",
                    message = "Storage is unavailable right now.",
                    onRetry = { demoState = DemoState.Loading },
                )
            }
        }

        Spacer(Modifier.height(PaperTheme.spacing.xxxl))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun PaperShowcaseLightPreview() {
    PaperTheme(darkTheme = false) { PaperShowcaseScreen() }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun PaperShowcaseDarkPreview() {
    PaperTheme(darkTheme = true) { PaperShowcaseScreen() }
}

/** Guards against the layout breaking at the largest accessibility font scales. */
@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 1.5f)
@Composable
private fun PaperShowcaseLargeFontPreview() {
    PaperTheme(darkTheme = false) { PaperShowcaseScreen() }
}
