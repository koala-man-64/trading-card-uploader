package com.tradingcards.uploader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Suppress("ktlint:standard:property-naming")
internal val LocalStatusPalette =
    staticCompositionLocalOf { LightStatusPalette }

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
fun UploaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val statusPalette = if (darkTheme) DarkStatusPalette else LightStatusPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalStatusPalette provides statusPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = UploaderTypography,
            content = content,
        )
    }
}
