package com.tradingcards.uploader.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage

private enum class PendingGalleryAction {
    Delete,
    Reprocess,
}

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
    val selectedCount = state.selectedNames.size

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Admin Gallery", style = MaterialTheme.typography.headlineSmall)
        CategoryTabs(state.category, onCategorySelected)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = !state.loading) {
                Text("Refresh")
            }
            OutlinedButton(
                onClick = { pendingAction = PendingGalleryAction.Delete },
                enabled = selectedCount > 0 && !state.loading,
            ) {
                Text("Delete")
            }
            OutlinedButton(
                onClick = { pendingAction = PendingGalleryAction.Reprocess },
                enabled = selectedCount > 0 && !state.loading,
            ) {
                Text("Reprocess")
            }
        }
        Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
        if (state.loading) {
            CircularProgressIndicator()
        }
        if (state.items.isEmpty() && !state.loading) {
            Text("No images found.", style = MaterialTheme.typography.bodyLarge)
        }
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
                    accessToken = state.accessToken,
                    repository = repository,
                    onToggleSelected = { onToggleSelected(image) },
                )
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(if (action == PendingGalleryAction.Delete) "Delete selected" else "Reprocess selected")
            },
            text = {
                Text("Apply this action to $selectedCount selected item(s)?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        if (action == PendingGalleryAction.Delete) {
                            onDeleteSelected()
                        } else {
                            onReprocessSelected()
                        }
                    },
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel")
                }
            },
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
            val buttonText =
                when (category) {
                    GalleryCategory.Raw -> "Raw"
                    GalleryCategory.Processed -> "Processed"
                    GalleryCategory.Segmented -> "Segmented"
                }
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

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryImageCard(
    image: GalleryImage,
    selected: Boolean,
    accessToken: String?,
    repository: GalleryRepository,
    onToggleSelected: () -> Unit,
) {
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleSelected),
    ) {
        Box {
            GalleryPreview(
                image = image,
                accessToken = accessToken,
                repository = repository,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
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
                image.sourceBlobName ?: "No lineage",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryPreview(
    image: GalleryImage,
    accessToken: String?,
    repository: GalleryRepository,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(image.previewUrl, accessToken) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(image.previewUrl, accessToken) {
        bitmap = accessToken?.let { repository.loadPreview(it, image) }
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
            Text("Preview")
        } else {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
