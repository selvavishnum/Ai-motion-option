package com.aimotion.handsfree.ui.paper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Visual weight of a [PaperButton]. Weight communicates priority, so a screen should carry at
 * most one [Primary] — if everything is emphasised, nothing is.
 */
enum class PaperButtonVariant {
    /** Filled accent. The single main action of a screen. */
    Primary,

    /** Hairline outline on surface. Secondary actions that still need a visible boundary. */
    Secondary,

    /** Text only. Tertiary actions, and anything inline in dense lists. */
    Ghost,

    /** Filled danger. Destructive, irreversible actions only. */
    Danger,
}

enum class PaperButtonSize { Medium, Small }

/**
 * The system's button.
 *
 * Behaviour worth knowing:
 * - **Loading implies disabled.** `loading = true` blocks input on its own, so callers cannot
 *   create the classic double-submit bug by forgetting to also pass `enabled = false`.
 * - **The touch target floor is enforced here**, not left to the caller. [PaperButtonSize.Small]
 *   shrinks only visible padding and keeps a 48dp target — a control that *looks* compact is
 *   fine; one that is genuinely hard to hit is not.
 * - **The label ellipsises rather than wrapping or vanishing**, and the full string stays
 *   available to screen readers.
 *
 * @param text button label. Use a verb ("Save", not "OK").
 * @param onClick invoked on tap. Never called while [loading] or `!enabled`.
 * @param modifier standard Compose modifier, applied to the outermost node.
 * @param variant visual weight — see [PaperButtonVariant].
 * @param size visible density; does not affect the touch target.
 * @param enabled when false, greys out and stops accepting input.
 * @param loading swaps in a spinner and blocks input.
 * @param loadingLabel announced to screen readers while [loading], e.g. "Saving".
 * @param fillWidth stretch to the parent's width — the norm for stacked mobile actions.
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PaperButtonVariant = PaperButtonVariant.Primary,
    size: PaperButtonSize = PaperButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = "Loading",
    fillWidth: Boolean = false,
) {
    val colors = PaperTheme.colors
    val interactive = enabled && !loading
    val filled = variant == PaperButtonVariant.Primary || variant == PaperButtonVariant.Danger

    val container = when {
        !interactive && filled -> colors.sunken
        variant == PaperButtonVariant.Primary -> colors.accent
        variant == PaperButtonVariant.Danger -> colors.danger
        variant == PaperButtonVariant.Secondary -> colors.surface
        else -> Color.Transparent
    }
    val contentColor = when {
        !interactive -> colors.inkFaint
        filled -> colors.onAccent
        variant == PaperButtonVariant.Danger -> colors.danger
        else -> colors.accent
    }

    val verticalPadding = if (size == PaperButtonSize.Small) 8.dp else 13.dp
    val horizontalPadding = if (size == PaperButtonSize.Small) 12.dp else 20.dp
    val shape = RoundedCornerShape(PaperDimens.RadiusMd)

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = PaperDimens.MinTouchTarget)
            .clip(shape)
            .background(container)
            .then(
                if (variant == PaperButtonVariant.Secondary) {
                    Modifier.border(
                        PaperDimens.Hairline,
                        if (interactive) colors.rule else colors.sunken,
                        shape,
                    )
                } else Modifier
            )
            // Default indication is the theme ripple; not naming it keeps this compatible
            // across Compose versions that moved the ripple API.
            .clickable(enabled = interactive, role = Role.Button, onClick = onClick)
            .semantics {
                // Without this a screen reader reads only the stale label, giving no signal that
                // the action is already in flight.
                if (loading) stateDescription = loadingLabel
            }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    // Decorative: stateDescription above already announces the loading state,
                    // so leaving this readable would make the reader say it twice.
                    modifier = Modifier.size(16.dp).clearAndSetSemantics {},
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF9F5, widthDp = 340)
@Composable
private fun PaperButtonPreview() {
    PaperTheme(darkTheme = false) {
        Column(
            modifier = Modifier.background(PaperLightColors.paper).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaperButton("Save changes", {}, fillWidth = true)
            PaperButton("Cancel", {}, variant = PaperButtonVariant.Secondary, fillWidth = true)
            PaperButton("Learn more", {}, variant = PaperButtonVariant.Ghost)
            PaperButton("Delete", {}, variant = PaperButtonVariant.Danger)
            PaperButton("Saving", {}, loading = true, loadingLabel = "Saving", fillWidth = true)
            PaperButton("Unavailable", {}, enabled = false, fillWidth = true)
        }
    }
}
