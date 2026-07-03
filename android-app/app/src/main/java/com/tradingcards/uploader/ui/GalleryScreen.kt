package com.tradingcards.uploader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.ImageLoader
import coil.size.Size
import com.tradingcards.uploader.R
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.data.NetworkClients
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage

private enum class PendingGalleryAction {
    Delete,
    Reprocess,
}

// Bounds decode resolution so BitmapFactory downsamples grid thumbnails
// instead of decoding at full camera resolution on every load.
private const val SKELETON_TILE_COUNT = 6
private const val THUMBNAIL_TARGET_WIDTH_PX = 360
private const val THUMBNAIL_TARGET_HEIGHT_PX = 480
private const val VIEWER_TARGET_EDGE_PX = 1080
private val THUMBNAIL_TARGET_SIZE = Size(THUMBNAIL_TARGET_WIDTH_PX, THUMBNAIL_TARGET_HEIGHT_PX)
private val VIEWER_TARGET_SIZE = Size(VIEWER_TARGET_EDGE_PX, VIEWER_TARGET_EDGE_PX)

private data class GalleryPreviewLoader(
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

private data class ViewedGalleryImage(
    val image: GalleryImage,
    val bitmap: Bitmap?,
)

private data class GalleryPreviewOptions(
    val contentScale: ContentScale = ContentScale.Crop,
    val placeholder: String = "Preview",
    val initialBitmap: Bitmap? = null,
    val targetSize: Size = THUMBNAIL_TARGET_SIZE,
)

@Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    repository: GalleryRepository,
    modifier: Modifier = Modifier,
    onCategorySelected: (GalleryCategory) -> Unit,
    onRefresh: () -> Unit,
    onToggleSelected: (GalleryImage) -> Unit,
    onDeleteSelected: () -> Unit,
    onReprocessSelected: () -> Unit,
) {
    var pendingAction by remember { mutableStateOf<PendingGalleryAction?>(null) }
    var viewingImage by remember { mutableStateOf<ViewedGalleryImage?>(null) }
    val context = LocalContext.current
    val previewLoader =
        GalleryPreviewLoader(
            accessToken = state.accessToken,
            repository = repository,
            context = context,
            imageLoader = NetworkClients.imageLoader(context),
        )
    val selectedCount = state.selectedNames.size

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.headlineSmall)
        CategoryTabs(state.category, onCategorySelected)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.gallery_refresh))
            }
            OutlinedButton(
                onClick = { pendingAction = PendingGalleryAction.Delete },
                enabled = selectedCount > 0 && !state.loading,
            ) {
                Text(stringResource(R.string.gallery_delete))
            }
            OutlinedButton(
                onClick = { pendingAction = PendingGalleryAction.Reprocess },
                enabled = selectedCount > 0 && !state.loading,
            ) {
                Text(stringResource(R.string.gallery_reprocess))
            }
        }
        Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
        when {
            state.loading && state.items.isEmpty() -> GallerySkeletonGrid()
            state.items.isEmpty() -> GalleryEmptyState(state.category, onRefresh)
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { it.name }) { image ->
                        GalleryImageCard(
                            image = image,
                            selected = image.name in state.selectedNames,
                            previewLoader = previewLoader,
                            onToggleSelected = { onToggleSelected(image) },
                            onViewImage = { bitmap ->
                                viewingImage = ViewedGalleryImage(image, bitmap)
                            },
                        )
                    }
                }
        }
    }

    pendingAction?.let { action ->
        val itemWord =
            if (selectedCount == 1) {
                stringResource(R.string.gallery_item_singular)
            } else {
                stringResource(R.string.gallery_item_plural)
            }
        val isDelete = action == PendingGalleryAction.Delete
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                val titleRes =
                    if (isDelete) R.string.gallery_confirm_title_delete else R.string.gallery_confirm_title_reprocess
                Text(stringResource(titleRes, selectedCount, itemWord))
            },
            text = {
                val bodyRes =
                    if (isDelete) R.string.gallery_confirm_body_delete else R.string.gallery_confirm_body_reprocess
                Text(stringResource(bodyRes, itemWord))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        if (isDelete) {
                            onDeleteSelected()
                        } else {
                            onReprocessSelected()
                        }
                    },
                ) {
                    Text(stringResource(if (isDelete) R.string.gallery_delete else R.string.gallery_reprocess))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.gallery_cancel))
                }
            },
        )
    }

    viewingImage?.let { selection ->
        GalleryImageViewerDialog(
            selection = selection,
            previewLoader = previewLoader,
            onDismiss = { viewingImage = null },
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun CategoryTabs(
    selected: GalleryCategory,
    onCategorySelected: (GalleryCategory) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryCategory.entries.forEach { category ->
            val active = category == selected
            val buttonText = categoryLabel(category)
            if (active) {
                Button(onClick = { onCategorySelected(category) }) {
                    Text(buttonText)
                }
            } else {
                OutlinedButton(onClick = { onCategorySelected(category) }) {
                    Text(buttonText)
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(category: GalleryCategory): String =
    when (category) {
        GalleryCategory.Raw -> stringResource(R.string.gallery_category_raw)
        GalleryCategory.Processed -> stringResource(R.string.gallery_category_processed)
        GalleryCategory.Segmented -> stringResource(R.string.gallery_category_segmented)
    }

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GallerySkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(SKELETON_TILE_COUNT) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(180.dp),
            ) {}
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryEmptyState(
    category: GalleryCategory,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.gallery_empty_title, categoryLabel(category).lowercase()),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.gallery_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        OutlinedButton(onClick = onRefresh) {
            Text(stringResource(R.string.gallery_refresh))
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryImageCard(
    image: GalleryImage,
    selected: Boolean,
    previewLoader: GalleryPreviewLoader,
    onToggleSelected: () -> Unit,
    onViewImage: (Bitmap?) -> Unit,
) {
    val previewKey = galleryPreviewCacheKey(image, previewLoader.accessToken)
    var previewBitmap by remember(previewKey) { mutableStateOf<Bitmap?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleSelected),
    ) {
        Box {
            GalleryPreview(
                image = image,
                previewLoader = previewLoader,
                onBitmapLoaded = { previewBitmap = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                options = GalleryPreviewOptions(placeholder = stringResource(R.string.gallery_preview_placeholder)),
            )
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                image.name.substringAfterLast("/"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                image.sourceBlobName ?: stringResource(R.string.gallery_no_lineage),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onViewImage(previewBitmap) }) {
                    Text(stringResource(R.string.gallery_view))
                }
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryPreview(
    image: GalleryImage,
    previewLoader: GalleryPreviewLoader,
    onBitmapLoaded: (Bitmap) -> Unit = {},
    modifier: Modifier = Modifier,
    options: GalleryPreviewOptions = GalleryPreviewOptions(),
) {
    val accessToken = previewLoader.accessToken
    val previewKey = galleryPreviewCacheKey(image, accessToken)
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
        loading = accessToken != null
        attemptedPreviewUrl = image.previewUrl
        val loaded =
            accessToken?.let {
                previewLoader.repository.loadPreview(
                    context = previewLoader.context,
                    imageLoader = previewLoader.imageLoader,
                    accessToken = it,
                    image = image,
                    targetSize = options.targetSize,
                )
            }
        bitmap = loaded
        loaded?.let(onBitmapLoaded)
        loading = false
    }
    Box(
        modifier =
            modifier.background(
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap == null) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Text(options.placeholder)
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
private fun GalleryImageViewerDialog(
    selection: ViewedGalleryImage,
    previewLoader: GalleryPreviewLoader,
    onDismiss: () -> Unit,
) {
    val image = selection.image

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        image.name.substringAfterLast("/"),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.gallery_close))
                    }
                }
                GalleryPreview(
                    image = image,
                    previewLoader = previewLoader,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 560.dp),
                    options =
                        GalleryPreviewOptions(
                            contentScale = ContentScale.Fit,
                            placeholder = stringResource(R.string.gallery_image_unavailable),
                            initialBitmap = selection.bitmap,
                            targetSize = VIEWER_TARGET_SIZE,
                        ),
                )
                Text(
                    image.sourceBlobName ?: image.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
