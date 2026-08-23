package com.aimotion.handsfree.ui.paper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A generic list row: optional leading glyph, a title, an optional supporting line, and an
 * optional trailing slot.
 *
 * Slot-based rather than parameter-per-widget on purpose — the trailing area is a
 * `@Composable` slot, so a chevron, a value, a badge or a button all work without this
 * component growing a new flag each time a screen needs something different.
 *
 * @param title primary label. Never truncated to nothing — ellipsises at two lines.
 * @param modifier standard modifier.
 * @param subtitle optional supporting line, muted.
 * @param leading optional leading glyph. Treated as decorative, so pass meaning via [title].
 * @param onClick when non-null the whole row becomes one tap target with a Button role.
 * @param trailing optional trailing slot.
 */
@Composable
fun PaperRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PaperDimens.RadiusSm))
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .defaultMinSize(minHeight = PaperDimens.MinTouchTarget)
            .padding(vertical = PaperTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
    ) {
        if (leading != null) {
            Text(
                text = leading,
                style = MaterialTheme.typography.titleLarge,
                // Decorative: an emoji read aloud as "raised hand sign" adds noise, not meaning.
                modifier = Modifier.width(32.dp).clearAndSetSemantics {},
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PaperTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A labelled switch row.
 *
 * The entire row toggles, not just the switch — a 48dp-wide thumb is a needlessly small target
 * when the row is already there. Semantics are merged so a screen reader announces
 * "title, description, on" as one node rather than three separate stops.
 *
 * @param title what the setting controls.
 * @param checked current state.
 * @param onCheckedChange invoked with the new state. Not called when [enabled] is false.
 * @param modifier standard modifier.
 * @param description optional supporting line explaining the consequence of the setting.
 * @param enabled when false, dims and stops accepting input.
 */
@Composable
fun PaperSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PaperDimens.RadiusSm))
            .toggleableRow(checked, enabled, onCheckedChange)
            .defaultMinSize(minHeight = PaperDimens.MinTouchTarget)
            .padding(vertical = PaperTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) PaperTheme.colors.ink else PaperTheme.colors.inkFaint,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) PaperTheme.colors.inkMuted else PaperTheme.colors.inkFaint,
                )
            }
        }
        Switch(
            checked = checked,
            // null: the row above owns the toggle semantics and the click. Leaving this wired
            // would give a screen reader two separate controls for one setting.
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = PaperTheme.colors.surface,
                checkedTrackColor = PaperTheme.colors.accent,
                uncheckedThumbColor = PaperTheme.colors.surface,
                uncheckedTrackColor = PaperTheme.colors.inkFaint,
                uncheckedBorderColor = PaperTheme.colors.inkFaint,
            ),
        )
    }
}

/** Extracted so the toggleable wiring and its semantics stay in one place. */
private fun Modifier.toggleableRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
): Modifier = this
    // toggleable also merges descendant semantics, which is what collapses the title,
    // description and switch into a single screen-reader stop.
    .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onCheckedChange,
    )
    .semantics {
        // Spoken instead of the default "on/off", which is accurate but tells the user nothing
        // about what it applies to when they land mid-list.
        stateDescription = if (checked) "On" else "Off"
    }

/**
 * A compact status chip: a state word plus a tone. Used for permission and health readouts.
 *
 * Tone is never the only carrier of meaning — the label always states the status in words, so
 * the chip still works for a colour-blind reader or in greyscale.
 */
@Composable
fun PaperStatusPill(
    label: String,
    tone: PaperTone,
    modifier: Modifier = Modifier,
) {
    val colors = PaperTheme.colors
    val (fg, bg) = when (tone) {
        PaperTone.Positive -> colors.success to colors.accentSoft
        PaperTone.Critical -> colors.danger to colors.dangerSoft
        PaperTone.Neutral -> colors.inkMuted to colors.sunken
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PaperDimens.RadiusPill))
            .background(bg)
            .padding(horizontal = PaperTheme.spacing.md, vertical = PaperTheme.spacing.xs),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

enum class PaperTone { Positive, Neutral, Critical }

@Preview(showBackground = true, backgroundColor = 0xFFFAF9F5, widthDp = 360)
@Composable
private fun PaperRowsPreview() {
    PaperTheme(darkTheme = false) {
        PaperScreen {
            Column(modifier = Modifier.padding(vertical = 24.dp)) {
                PaperSwitchRow(
                    title = "Air gestures",
                    checked = true,
                    onCheckedChange = {},
                    description = "Hand poses control the screen.",
                )
                PaperRule()
                PaperSwitchRow(
                    title = "Face gestures",
                    checked = false,
                    onCheckedChange = {},
                    description = "Blink and eyebrow triggers.",
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
        }
    }
}
