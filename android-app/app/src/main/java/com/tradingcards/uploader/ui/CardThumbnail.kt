package com.tradingcards.uploader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Card-shaped thumbnail for a captured photo, loaded by Coil over a neutral placeholder. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun CardThumbnail(localUri: String) {
    Box(
        modifier =
            Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = localUri,
            contentDescription = "Captured card",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
