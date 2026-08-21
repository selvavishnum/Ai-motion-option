package com.aimotion.handsfree.ui.mapping

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF3D6BFF)
private val BrandAccent = Color(0xFF8AB4FF)

private val DarkColors = darkColorScheme(
    primary = Brand,
    secondary = BrandAccent,
    background = Color(0xFF0B0C10),
    surface = Color(0xFF16171D),
    surfaceVariant = Color(0xFF1E2028),
    onBackground = Color(0xFFE8E8EC),
    onSurface = Color(0xFFE8E8EC),
    onSurfaceVariant = Color(0xFF9A9AA2),
    error = Color(0xFFF26C6C),
)

// The rest of the app is dark-only today, but real production apps get a light variant for
// free with dynamic/system theming — defined now so it's a one-line switch to enable later,
// not a redesign.
private val LightColors = lightColorScheme(
    primary = Brand,
    secondary = BrandAccent,
    background = Color(0xFFFAFAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF5),
    onBackground = Color(0xFF16171D),
    onSurface = Color(0xFF16171D),
    onSurfaceVariant = Color(0xFF5B5D66),
    error = Color(0xFFB3261E),
)

/** Wraps Compose content in the app's Material3 theme. Currently always renders the dark
 * palette to match the rest of the (View-based) app; pass [forceDark] = false, or switch the
 * default to [isSystemInDarkTheme], to opt individual screens into following the system theme
 * without touching any component below this. */
@Composable
fun MappingTheme(forceDark: Boolean = true, content: @Composable () -> Unit) {
    val useDark = forceDark || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        content = content,
    )
}
