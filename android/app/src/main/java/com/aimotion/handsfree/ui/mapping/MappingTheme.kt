package com.aimotion.handsfree.ui.mapping

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Compose half of the app's paper-white palette, matching res/values/colors.xml value for value.
 * The two exist separately because half the app is Views and half is Compose; they are kept in
 * lockstep by hand, so a colour changed in one belongs changed in the other.
 */
private val Ink = Color(0xFF16181D)
private val Muted = Color(0xFF6B7280)
private val Brand = Color(0xFF2563EB)

private val PaperColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Brand,
    // Page and card are the same pure white; hairline outlines carry the structure instead of a
    // background/surface contrast. See colors.xml for why the page is not tinted.
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF6F7F9),
    onSurfaceVariant = Muted,
    outline = Color(0xFFE4E6EB),
    outlineVariant = Color(0xFFE4E6EB),
    error = Color(0xFFB3261E),
)

/**
 * Wraps Compose content in the app's theme.
 *
 * Light-only on purpose, and no [androidx.compose.foundation.isSystemInDarkTheme] switch: the
 * palette, the hairline borders and the flat white cards are designed for one background, and a
 * dark variant is a design exercise rather than a swapped colour scheme. Shipping a half-designed
 * dark mode would be worse than not having one.
 */
@Composable
fun MappingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PaperColors, content = content)
}
