package com.tradingcards.uploader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.R
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.data.NetworkClients
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage

private enum class PendingGalleryAction {
    Delete,
    Reprocess,
}

private const val SKELETON_TILE_COUNT = 9

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
    onClearSelection: () -> Unit,
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
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GalleryHeader(state, onRefresh)
        CategorySelector(state.category, enabled = !state.loading, onCategorySelected)
        state.errorText?.let { ErrorNotice(it) }
        if (selectedCount > 0) {
            SelectionBar(
                selectedCount = selectedCount,
                enabled = !state.loading,
                onReprocess = { pendingAction = PendingGalleryAction.Reprocess },
                onDelete = { pendingAction = PendingGalleryAction.Delete },
                onClear = onClearSelection,
            )
        }
        when {
            state.loading && state.items.isEmpty() -> GallerySkeletonGrid()
            state.items.isEmpty() -> GalleryEmptyState(state.category, onRefresh)
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.name }) { image ->
                        GalleryImageTile(
                            image = image,
                            selected = image.name in state.selectedNames,
                            selectionActive = selectedCount > 0,
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
        GalleryConfirmDialog(
            action = action,
            selectedCount = selectedCount,
            onConfirm = {
                pendingAction = null
                if (action == PendingGalleryAction.Delete) {
                    onDeleteSelected()
                } else {
                    onReprocessSelected()
                }
            },
            onDismiss = { pendingAction = null },
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
private fun GalleryHeader(
    state: GalleryUiState,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.gallery_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val subtitle =
                if (state.loading && state.items.isEmpty()) {
                    stringResource(R.string.gallery_loading)
                } else {
                    pluralStringResource(R.plurals.gallery_image_count, state.items.size, state.items.size)
                }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh, enabled = !state.loading) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.gallery_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun CategorySelector(
    selected: GalleryCategory,
    enabled: Boolean,
    onCategorySelected: (GalleryCategory) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            GalleryCategory.entries.forEach { category ->
                val active = category == selected
                Surface(
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = CircleShape,
                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .clickable(enabled = enabled) { onCategorySelected(category) },
                ) {
                    Text(
                        categoryLabel(category),
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                    )
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
private fun ErrorNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SelectionBar(
    selectedCount: Int,
    enabled: Boolean,
    onReprocess: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
        ) {
            Text(
                stringResource(R.string.gallery_selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReprocess, enabled = enabled) {
                Text(stringResource(R.string.gallery_reprocess))
            }
            TextButton(
                onClick = onDelete,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.gallery_delete))
            }
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.gallery_clear_selection),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GallerySkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(SKELETON_TILE_COUNT) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = TileShape,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(TILE_ASPECT_RATIO),
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
        modifier = Modifier.fillMaxSize(),
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
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        OutlinedButton(onClick = onRefresh) {
            Text(stringResource(R.string.gallery_refresh))
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryConfirmDialog(
    action: PendingGalleryAction,
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val itemWord =
        if (selectedCount == 1) {
            stringResource(R.string.gallery_item_singular)
        } else {
            stringResource(R.string.gallery_item_plural)
        }
    val isDelete = action == PendingGalleryAction.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (isDelete) R.string.gallery_delete else R.string.gallery_reprocess))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.gallery_cancel))
            }
        },
    )
}
