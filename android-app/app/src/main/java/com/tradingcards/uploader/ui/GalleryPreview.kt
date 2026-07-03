package com.tradingcards.uploader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.ImageLoader
import coil.size.Size
import com.tradingcards.uploader.R
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.model.GalleryImage

// Bounds decode resolution so BitmapFactory downsamples grid thumbnails
// instead of decoding at full camera resolution on every load.
private const val THUMBNAIL_TARGET_WIDTH_PX = 360
private const val THUMBNAIL_TARGET_HEIGHT_PX = 480
private const val VIEWER_TARGET_EDGE_PX = 1080
private val THUMBNAIL_TARGET_SIZE = Size(THUMBNAIL_TARGET_WIDTH_PX, THUMBNAIL_TARGET_HEIGHT_PX)
private val VIEWER_TARGET_SIZE = Size(VIEWER_TARGET_EDGE_PX, VIEWER_TARGET_EDGE_PX)

internal data class GalleryPreviewLoader(
    val accessToken: String?,
    val repository: GalleryRepository,
    val context: Context,
    val imageLoader: ImageLoader,
)

private data class GalleryPreviewCacheKey(
    val name: String,
    val lastModifiedUtc: String?,
    val size: Long,
    val accessToken: String?,
)

internal data class ViewedGalleryImage(
    val image: GalleryImage,
    val bitmap: Bitmap?,
)

internal data class GalleryPreviewOptions(
    val contentScale: ContentScale = ContentScale.Crop,
    val placeholder: String = "",
    val initialBitmap: Bitmap? = null,
    val targetSize: Size = THUMBNAIL_TARGET_SIZE,
)

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun GalleryPreview(
    image: GalleryImage,
    previewLoader: GalleryPreviewLoader,
    onBitmapLoaded: (Bitmap) -> Unit = {},
    modifier: Modifier = Modifier,
    options: GalleryPreviewOptions = GalleryPreviewOptions(),
) {
    val previewKey = galleryPreviewCacheKey(image, previewLoader.accessToken)
    var bitmap by remember(previewKey) { mutableStateOf(options.initialBitmap) }
    var loading by remember(previewKey) { mutableStateOf(false) }
    var attemptedPreviewUrl by remember(previewKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(previewKey, image.previewUrl) {
        if (bitmap != null) {
            loading = false
            return@LaunchedEffect
        }
        if (attemptedPreviewUrl == image.previewUrl) {
            return@LaunchedEffect
        }
        bitmap = null
        loading = previewLoader.accessToken != null
        attemptedPreviewUrl = image.previewUrl
        val loaded = loadPreviewBitmap(previewLoader, image, options.targetSize)
        bitmap = loaded
        loaded?.let(onBitmapLoaded)
        loading = false
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap == null) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Text(
                    options.placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription =
                    stringResource(R.string.gallery_image_content_description, image.name.substringAfterLast("/")),
                contentScale = options.contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun loadPreviewBitmap(
    previewLoader: GalleryPreviewLoader,
    image: GalleryImage,
    targetSize: Size,
): Bitmap? =
    previewLoader.accessToken?.let { token ->
        previewLoader.repository.loadPreview(
            context = previewLoader.context,
            imageLoader = previewLoader.imageLoader,
            accessToken = token,
            image = image,
            targetSize = targetSize,
        )
    }

private fun galleryPreviewCacheKey(
    image: GalleryImage,
    accessToken: String?,
): GalleryPreviewCacheKey =
    GalleryPreviewCacheKey(
        name = image.name,
        lastModifiedUtc = image.lastModifiedUtc,
        size = image.size,
        accessToken = accessToken,
    )

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun GalleryImageViewerDialog(
    selection: ViewedGalleryImage,
    previewLoader: GalleryPreviewLoader,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GalleryPreview(
                    image = selection.image,
                    previewLoader = previewLoader,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .heightIn(min = 240.dp, max = 560.dp),
                    options =
                        GalleryPreviewOptions(
                            contentScale = ContentScale.Fit,
                            placeholder = stringResource(R.string.gallery_image_unavailable),
                            initialBitmap = selection.bitmap,
                            targetSize = VIEWER_TARGET_SIZE,
                        ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.gallery_close))
                    }
                }
            }
        }
    }
}
