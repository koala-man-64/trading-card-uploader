package com.tradingcards.uploader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.R
import com.tradingcards.uploader.data.galleryPreviewMemoryCacheKey
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.cardDisplayName
import com.tradingcards.uploader.model.cardPriceText

internal const val TILE_ASPECT_RATIO = 3f / 4f
private const val TOGGLE_SCRIM_ALPHA = 0.35f
private const val TOGGLE_RING_ALPHA = 0.9f
private const val NAME_SCRIM_ALPHA = 0.55f

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
    onViewImage: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewMemoryCacheKey by remember(image) { mutableStateOf<String?>(null) }
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, label = "gallery-tile-border-width")
    val borderColor by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            label = "gallery-tile-border-color",
        )
    val border = BorderStroke(borderWidth, borderColor)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = TileShape,
        border = border,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(TileShape)
                .clickable {
                    if (selectionActive) {
                        onToggleSelected()
                    } else {
                        onViewImage(
                            previewMemoryCacheKey
                                ?: galleryPreviewMemoryCacheKey(image, THUMBNAIL_CACHE_VARIANT),
                        )
                    }
                },
    ) {
        Box(modifier = Modifier.aspectRatio(TILE_ASPECT_RATIO)) {
            GalleryPreview(
                image = image,
                previewLoader = previewLoader,
                onMemoryCacheKeyLoaded = { previewMemoryCacheKey = it },
                modifier = Modifier.fillMaxSize(),
            )
            CardMetadataLabel(
                name = image.cardDisplayName() ?: stringResource(R.string.gallery_unknown_card),
                price = image.cardPriceText() ?: stringResource(R.string.gallery_no_price),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
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

/**
 * Overlays scanner-extracted metadata so processed/segmented tiles identify
 * the card at a glance.
 */
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun CardMetadataLabel(
    name: String,
    price: String,
    modifier: Modifier = Modifier,
) {
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = NAME_SCRIM_ALPHA)
    Column(
        modifier =
            modifier
                .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            price,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SelectionToggle(
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by
        animateColorAsState(
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.scrim.copy(alpha = TOGGLE_SCRIM_ALPHA)
            },
            label = "gallery-toggle-background",
        )
    val ring by
        animateColorAsState(
            if (selected) {
                Color.Transparent
            } else {
                Color.White.copy(alpha = TOGGLE_RING_ALPHA)
            },
            label = "gallery-toggle-ring",
        )
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
