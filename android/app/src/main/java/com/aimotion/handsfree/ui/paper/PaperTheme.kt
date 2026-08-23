package com.aimotion.handsfree.ui.paper

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography for the paper system. Weight and spacing carry the hierarchy rather than size —
 * a minimalist screen with four font sizes reads as considered; the same screen with eight
 * reads as noisy. Negative tracking on the large sizes is what keeps big text from looking
 * loose, and positive tracking on the smallest keeps captions legible.
 *
 * Sizes are in `sp`, never `dp`, so the whole system scales with the reader's font-size setting.
 */
private val PaperTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
    ),
)

/**
 * True when the device has animations switched on. Users who set "Remove animations" in
 * Accessibility, or turn animation scale off in Developer options, are telling us that motion
 * causes them problems — vestibular disorders make looping animation genuinely unpleasant, not
 * merely unwanted. Components read this to fall back to a static presentation.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * Wraps content in the Paper design system.
 *
 * Provides both the paper tokens ([PaperTheme.colors], [PaperTheme.spacing]) and a matching
 * Material3 [MaterialTheme], so stock Material components dropped into a paper screen still
 * inherit the right palette instead of reverting to purple.
 *
 * @param darkTheme follow the system by default. Paper is a light-first design, but the tokens
 *   carry a dim counterpart so this is a supported mode, not an afterthought.
 */
@Composable
fun PaperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) PaperDarkColors else PaperLightColors

    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        // Global.ANIMATOR_DURATION_SCALE is 0 when the user has disabled animations.
        // runCatching: this is a system setting read, and a missing/blocked setting must not be
        // able to take a screen down.
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.paper, onBackground = colors.ink,
            surface = colors.surface, onSurface = colors.ink,
            surfaceVariant = colors.sunken, onSurfaceVariant = colors.inkMuted,
            outline = colors.rule, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.paper, onBackground = colors.ink,
            surface = colors.surface, onSurface = colors.ink,
            surfaceVariant = colors.sunken, onSurfaceVariant = colors.inkMuted,
            outline = colors.rule, error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalPaperColors provides colors,
        LocalPaperSpacing provides PaperSpacing(),
        LocalAnimationsEnabled provides animationsEnabled,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = PaperTypography,
            content = content,
        )
    }
}

/**
 * Token accessor, mirroring how `MaterialTheme.colorScheme` reads at call sites. Same name as
 * the composable above on purpose — Kotlin keeps callables and classifiers in separate
 * namespaces, which is exactly the trick `MaterialTheme` itself uses.
 */
object PaperTheme {
    val colors: PaperColors
        @Composable @ReadOnlyComposable get() = LocalPaperColors.current

    val spacing: PaperSpacing
        @Composable @ReadOnlyComposable get() = LocalPaperSpacing.current
}
