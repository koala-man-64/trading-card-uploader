package com.tradingcards.uploader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.R
import com.tradingcards.uploader.model.GalleryImage

internal const val TILE_ASPECT_RATIO = 3f / 4f
private const val TOGGLE_SCRIM_ALPHA = 0.35f
private const val TOGGLE_RING_ALPHA = 0.9f

@Suppress("ktlint:standard:property-naming")
internal val TileShape = RoundedCornerShape(14.dp)

@Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")
@Composable
internal fun GalleryImageTile(
    image: GalleryImage,
    selected: Boolean,
    selectionActive: Boolean,
    previewLoader: GalleryPreviewLoader,
    onToggleSelected: () -> Unit,
    onViewImage: (Bitmap?) -> Unit,
) {
    var previewBitmap by remember(image, previewLoader.accessToken) { mutableStateOf<Bitmap?>(null) }
    val border =
        if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = TileShape,
        border = border,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TileShape)
                .clickable {
                    if (selectionActive) {
                        onToggleSelected()
                    } else {
                        onViewImage(previewBitmap)
                    }
                },
    ) {
        Box(modifier = Modifier.aspectRatio(TILE_ASPECT_RATIO)) {
            GalleryPreview(
                image = image,
                previewLoader = previewLoader,
                onBitmapLoaded = { previewBitmap = it },
                modifier = Modifier.fillMaxSize(),
            )
            SelectionToggle(
                selected = selected,
                onToggle = onToggleSelected,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SelectionToggle(
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.scrim.copy(alpha = TOGGLE_SCRIM_ALPHA)
        }
    val ring =
        if (selected) {
            Color.Transparent
        } else {
            Color.White.copy(alpha = TOGGLE_RING_ALPHA)
        }
    Box(
        modifier =
            modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(background)
                .border(1.dp, ring, CircleShape)
                .clickable(
                    onClickLabel = stringResource(R.string.gallery_select_toggle),
                    onClick = onToggle,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
