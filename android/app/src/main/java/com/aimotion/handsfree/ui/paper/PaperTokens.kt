package com.aimotion.handsfree.ui.paper

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the "Paper" system: a minimalist, paper-white surface treatment.
 *
 * Tokens exist so no component ever hard-codes a raw value. A component asks for
 * `PaperTheme.colors.inkMuted`, never `Color(0xFF6B6862)` — which is what makes a palette change
 * a one-file edit instead of a search-and-replace across the UI.
 *
 * Design rules encoded here, so they hold automatically:
 * - **No pure white, no pure black.** Paper is warm (#FAF9F5) and ink is soft (#1A1A18); pure
 *   #FFF/#000 on an OLED phone glares and reads as cheap.
 * - **Hairlines, not shadows.** Minimalist depth comes from a 1dp rule and a tone shift, so
 *   there is a [rule] colour and no elevation token.
 * - **One accent, used sparingly.** A single ink-blue carries every interactive affordance;
 *   colour is information, not decoration.
 */
@Immutable
data class PaperColors(
    /** Page background. */
    val paper: Color,
    /** Raised container sitting on [paper]. */
    val surface: Color,
    /** Inset/recessed fill — disabled controls, skeletons, code blocks. */
    val sunken: Color,
    /** Primary text and icons. */
    val ink: Color,
    /** Secondary text: descriptions, captions. Contrast-checked against [paper] for AA body. */
    val inkMuted: Color,
    /** Tertiary text: placeholders, timestamps. Decorative-weight only, never sole meaning. */
    val inkFaint: Color,
    /** 1dp hairline separators and borders. */
    val rule: Color,
    /** The single interactive accent. */
    val accent: Color,
    /** Tinted accent wash for selected/active backgrounds. */
    val accentSoft: Color,
    /** Foreground on [accent]. */
    val onAccent: Color,
    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
)

/**
 * The paper palette. Contrast against [PaperColors.paper] (#FAF9F5):
 * ink ≈ 16.5:1, inkMuted ≈ 5.4:1, accent ≈ 8.1:1 — all clear AA for body text, ink and accent
 * clear AAA. inkFaint (≈3.1:1) intentionally does **not** reach AA and is therefore restricted
 * to text that duplicates information available elsewhere.
 */
val PaperLightColors = PaperColors(
    paper = Color(0xFFFAF9F5),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFF2F0EA),
    ink = Color(0xFF1A1A18),
    inkMuted = Color(0xFF6B6862),
    inkFaint = Color(0xFF9A968E),
    rule = Color(0xFFE4E1D9),
    accent = Color(0xFF2B4C7E),
    accentSoft = Color(0xFFEDF1F7),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFF9C3B2E),
    dangerSoft = Color(0xFFF9EDEB),
    success = Color(0xFF3E6B4F),
)

/**
 * A dim counterpart, so a screen built on these tokens survives a dark-mode request without
 * being rewritten. It keeps the paper *character* — warm, low-chroma, hairline-led — rather than
 * flipping to a cold grey, and preserves every token's semantic role.
 */
val PaperDarkColors = PaperColors(
    paper = Color(0xFF14140F),
    surface = Color(0xFF1C1C16),
    sunken = Color(0xFF24241D),
    ink = Color(0xFFF0EEE6),
    inkMuted = Color(0xFFA8A49A),
    inkFaint = Color(0xFF77736A),
    rule = Color(0xFF33322A),
    accent = Color(0xFF9DB8E0),
    accentSoft = Color(0xFF232934),
    onAccent = Color(0xFF10161F),
    danger = Color(0xFFE0907F),
    dangerSoft = Color(0xFF2E1F1C),
    success = Color(0xFF8FBFA0),
)

/**
 * A 4dp-based spacing scale. A closed set of steps is the point: it stops the slow drift into
 * 13dp/17dp/23dp one-offs that makes a UI look subtly untidy without anyone being able to say why.
 */
@Immutable
data class PaperSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

object PaperDimens {
    /** Android's minimum accessible touch target. Every interactive component enforces it. */
    val MinTouchTarget = 48.dp

    /** Text measured beyond ~70 characters per line is measurably harder to scan, and a phone
     * layout stretched across a tablet or unfolded foldable looks broken. Content centres and
     * stops here instead. */
    val ContentMaxWidth = 600.dp

    val Hairline = 1.dp

    /** Minimalist means restrained curvature: enough to feel soft, not enough to be a motif. */
    val RadiusSm = 6.dp
    val RadiusMd = 10.dp
    val RadiusLg = 14.dp
    val RadiusPill = 999.dp
}

val LocalPaperColors = staticCompositionLocalOf { PaperLightColors }
val LocalPaperSpacing = staticCompositionLocalOf { PaperSpacing() }
