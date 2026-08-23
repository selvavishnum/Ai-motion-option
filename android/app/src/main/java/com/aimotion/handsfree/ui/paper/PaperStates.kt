package com.aimotion.handsfree.ui.paper

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A single shimmering placeholder bar.
 *
 * Honours [LocalAnimationsEnabled]: when a user has asked the system to remove animations, this
 * renders as a flat block. Looping motion is genuinely unpleasant for people with vestibular
 * disorders, so that setting is an instruction, not a preference to work around.
 */
@Composable
fun PaperSkeletonBar(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 14.dp,
    widthFraction: Float = 1f,
) {
    val animate = LocalAnimationsEnabled.current
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val animated by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonAlpha",
        )
        animated
    } else {
        0.6f
    }

    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(PaperDimens.RadiusSm))
            .alpha(alpha)
            .background(PaperTheme.colors.sunken)
    )
}

/**
 * Loading state for list-shaped content.
 *
 * A skeleton rather than a centred spinner, because it preserves the page's layout: the content
 * lands in the space its placeholder already occupied instead of the screen jumping when data
 * arrives. The whole block is collapsed to one accessibility node announcing [label] — a screen
 * reader has nothing useful to say about eight grey rectangles.
 *
 * @param label announced while loading, e.g. "Loading gesture mappings".
 * @param rows how many placeholder rows to draw. Match the real content's typical length.
 */
@Composable
fun PaperLoadingState(
    label: String,
    modifier: Modifier = Modifier,
    rows: Int = 5,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg),
    ) {
        repeat(rows) { index ->
            Column(
                modifier = Modifier.padding(vertical = PaperTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
            ) {
                PaperSkeletonBar(height = 16.dp, widthFraction = 0.45f)
                // Varying the widths stops the placeholder reading as a rigid grid, which is
                // what makes a skeleton look like content rather than a loading graphic.
                PaperSkeletonBar(height = 12.dp, widthFraction = if (index % 2 == 0) 0.7f else 0.55f)
            }
        }
    }
}

/**
 * Empty state.
 *
 * Every empty state should answer two questions: why is this empty, and what can I do about it.
 * [body] covers the first and [action] the second — an empty state with neither is a dead end,
 * which is why [title] and [body] are both required rather than optional.
 *
 * @param title short statement of the situation. Not an apology.
 * @param body one or two sentences explaining why, in the user's terms.
 * @param glyph decorative mark, hidden from screen readers.
 * @param action optional recovery affordance.
 */
@Composable
fun PaperEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    glyph: String? = "◌",
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PaperTheme.spacing.xxxl, horizontal = PaperTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.headlineMedium,
                color = PaperTheme.colors.inkFaint,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = PaperTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = PaperTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Box(modifier = Modifier.padding(top = PaperTheme.spacing.sm)) { action() }
        }
    }
}

/**
 * Error state with recovery.
 *
 * A polite live region, so the failure is announced when it appears rather than waiting for the
 * user to swipe-navigate onto it. [onRetry] is required, not optional: an error the user cannot
 * act on is a dead end, and making retry mandatory in the API stops that shipping by accident.
 *
 * @param title short, human summary — "Couldn't load your settings", not "Error 500".
 * @param message the specific cause, if one can be stated usefully.
 * @param onRetry re-attempt the failed operation.
 * @param retryLabel verb for the retry control.
 */
@Composable
fun PaperErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PaperTheme.spacing.xxxl, horizontal = PaperTheme.spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = PaperTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = PaperTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        Box(modifier = Modifier.padding(top = PaperTheme.spacing.sm)) {
            PaperButton(
                text = retryLabel,
                onClick = onRetry,
                variant = PaperButtonVariant.Secondary,
                size = PaperButtonSize.Small,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF9F5, widthDp = 360)
@Composable
private fun PaperLoadingPreview() {
    PaperTheme(darkTheme = false) {
        PaperScreen { PaperLoadingState(label = "Loading gesture mappings", rows = 3) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF9F5, widthDp = 360)
@Composable
private fun PaperEmptyPreview() {
    PaperTheme(darkTheme = false) {
        PaperScreen {
            PaperEmptyState(
                title = "No gestures configured",
                body = "Add a gesture to start controlling your phone hands-free.",
                action = { PaperButton("Add gesture", {}, size = PaperButtonSize.Small) },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF9F5, widthDp = 360)
@Composable
private fun PaperErrorPreview() {
    PaperTheme(darkTheme = false) {
        PaperScreen {
            PaperErrorState(
                title = "Couldn't load your settings",
                message = "Storage is unavailable right now.",
                onRetry = {},
            )
        }
    }
}
