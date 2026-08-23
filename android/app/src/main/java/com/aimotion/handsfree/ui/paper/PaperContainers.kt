package com.aimotion.handsfree.ui.paper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A 1dp hairline. The paper system's only separator: minimalist depth comes from a rule and a
 * tone shift, never a drop shadow.
 */
@Composable
fun PaperRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(PaperDimens.Hairline)
            .background(PaperTheme.colors.rule)
    )
}

/**
 * A raised container on the page background.
 *
 * Deliberately has no elevation parameter. Shadow is not part of this system, so offering the
 * knob would only invite screens that drift away from it.
 *
 * @param modifier standard modifier.
 * @param bordered draw a hairline boundary. Keep it on where the card sits directly on [PaperColors.paper]
 *   and the tonal difference alone is too subtle to read as an edge.
 * @param contentPadding inner padding. Pass `0.dp` when the card holds full-bleed rows that
 *   supply their own padding, so row dividers can run edge to edge.
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    bordered: Boolean = true,
    contentPadding: androidx.compose.ui.unit.Dp = PaperTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PaperDimens.RadiusLg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaperTheme.colors.surface)
            .then(
                if (bordered) Modifier.border(PaperDimens.Hairline, PaperTheme.colors.rule, shape)
                else Modifier
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A section label above a group of content.
 *
 * Marked as a heading for accessibility, which is what lets screen-reader users jump between
 * sections instead of swiping through every row to find where a group starts.
 *
 * **Pass [subtitle] by name.** Compose convention puts `modifier` immediately after the required
 * parameters, so `PaperSectionHeader("Rows", "A subtitle")` binds the second string to `modifier`
 * and fails to compile. Every optional parameter in this system sits after `modifier` for
 * consistency with the rest of Compose, which means optional arguments are always named —
 * exactly as `Text(text, modifier, color, …)` requires.
 *
 * @param title the section name. Keep it to one or two words.
 * @param subtitle optional supporting line. Use it for guidance that would otherwise become a
 *   tooltip nobody reads.
 */
@Composable
fun PaperSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = PaperTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = PaperTheme.colors.inkMuted,
            )
        }
    }
}

/**
 * Screen shell: paints the page background and constrains content width.
 *
 * The width cap is the reason this exists. Without it every screen has to remember not to
 * stretch across a tablet or an unfolded foldable, and eventually one of them forgets.
 *
 * @param modifier standard modifier.
 * @param horizontalPadding page gutter. Shrink it on very narrow devices at the call site.
 */
@Composable
fun PaperScreen(
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = PaperTheme.spacing.lg,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PaperTheme.colors.paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PaperDimens.ContentMaxWidth)
                .fillMaxWidth()
                // Scroll by default: a screen that fits today stops fitting at a large font
                // scale or in split-screen, and a clipped settings page is a support ticket.
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = horizontalPadding),
            content = content,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF9F5, widthDp = 360)
@Composable
private fun PaperContainersPreview() {
    PaperTheme(darkTheme = false) {
        PaperScreen {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 24.dp)) {
                PaperSectionHeader("Detection", subtitle = "Which sensors are running.")
                PaperCard {
                    Text(
                        "Card content sits on a white surface with a hairline edge.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperTheme.colors.inkMuted,
                    )
                }
                PaperRule()
            }
        }
    }
}
