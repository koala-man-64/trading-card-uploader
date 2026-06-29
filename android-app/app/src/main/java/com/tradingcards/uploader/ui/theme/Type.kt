@file:Suppress("MagicNumber")

package com.tradingcards.uploader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

internal val UploaderTypography =
    Default.copy(
        headlineSmall =
            Default.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            ),
        titleLarge =
            Default.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        titleMedium =
            Default.titleMedium.copy(
                fontWeight = FontWeight.Medium,
            ),
        labelLarge =
            Default.labelLarge.copy(
                fontWeight = FontWeight.Medium,
            ),
        displaySmall =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.4).sp,
            ),
    )
