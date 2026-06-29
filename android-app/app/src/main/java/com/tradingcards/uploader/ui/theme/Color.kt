@file:Suppress("MagicNumber")

package com.tradingcards.uploader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Single restrained accent over a warm-neutral surface palette.
private val AccentLight = Color(0xFF3B5BDB)
private val AccentDark = Color(0xFFB6C2FF)

internal val LightColors =
    lightColorScheme(
        primary = AccentLight,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDEE4FB),
        onPrimaryContainer = Color(0xFF0E1B49),
        secondary = Color(0xFF565E71),
        onSecondary = Color(0xFFFFFFFF),
        background = Color(0xFFFBFBFA),
        onBackground = Color(0xFF1B1B19),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1B19),
        surfaceVariant = Color(0xFFF1F1EE),
        onSurfaceVariant = Color(0xFF5F5E5A),
        outline = Color(0xFFD7D6D1),
        outlineVariant = Color(0xFFE7E6E2),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

internal val DarkColors =
    darkColorScheme(
        primary = AccentDark,
        onPrimary = Color(0xFF142055),
        primaryContainer = Color(0xFF2C3A77),
        onPrimaryContainer = Color(0xFFDEE4FB),
        secondary = Color(0xFFBEC6DC),
        onSecondary = Color(0xFF283041),
        background = Color(0xFF131312),
        onBackground = Color(0xFFE6E5E1),
        surface = Color(0xFF1C1C1B),
        onSurface = Color(0xFFE6E5E1),
        surfaceVariant = Color(0xFF252523),
        onSurfaceVariant = Color(0xFFAFAEA9),
        outline = Color(0xFF3A3A38),
        outlineVariant = Color(0xFF2C2C2A),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

// Status accents that sit outside Material's role palette (success / waiting).
internal data class StatusPalette(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
)

internal val LightStatusPalette =
    StatusPalette(
        success = Color(0xFF1D7A4D),
        onSuccessContainer = Color(0xFF06301B),
        successContainer = Color(0xFFD3F0DD),
    )

internal val DarkStatusPalette =
    StatusPalette(
        success = Color(0xFF7BD6A0),
        onSuccessContainer = Color(0xFFCFEFDB),
        successContainer = Color(0xFF1E3A2A),
    )
