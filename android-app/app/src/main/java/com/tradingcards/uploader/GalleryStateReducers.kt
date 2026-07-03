package com.tradingcards.uploader

import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.ui.GalleryUiState
import kotlinx.coroutines.CancellationException

internal enum class GalleryRefreshReason {
    Initial,
    Category,
    Manual,
    Action,
    Poll,
}

internal data class LoadedGallery(
    val selectedCategory: GalleryCategory,
    val response: GalleryImagesResponse,
)

internal suspend fun loadGallery(
    token: String,
    category: GalleryCategory,
    repository: GalleryRepository,
): LoadedGallery =
    LoadedGallery(
        selectedCategory = category,
        response = repository.list(token, category),
    )

internal fun galleryStateForRefreshStart(
    state: GalleryUiState,
    category: GalleryCategory,
    reason: GalleryRefreshReason,
): GalleryUiState =
    when (reason) {
        GalleryRefreshReason.Poll -> state
        GalleryRefreshReason.Action ->
            state.copy(
                loading = true,
                errorText = null,
            )
        GalleryRefreshReason.Initial,
        GalleryRefreshReason.Category,
        GalleryRefreshReason.Manual,
        ->
            state.copy(
                category = category,
                loading = true,
                selectedNames = emptySet(),
                errorText = null,
            )
    }

internal fun galleryStateForRefreshSuccess(
    state: GalleryUiState,
    token: String,
    loaded: LoadedGallery,
    reason: GalleryRefreshReason,
): GalleryUiState {
    val loadedNames = loaded.response.items.map { it.name }.toSet()
    val selectedNames =
        if (reason == GalleryRefreshReason.Poll) {
            state.selectedNames.intersect(loadedNames)
        } else {
            emptySet()
        }
    return state.copy(
        category = loaded.selectedCategory,
        items = loaded.response.items,
        selectedNames = selectedNames,
        loading = false,
        errorText = null,
        accessToken = token,
    )
}

internal fun galleryStateForRefreshFailure(
    state: GalleryUiState,
    reason: GalleryRefreshReason,
    message: String?,
): GalleryUiState =
    when (reason) {
        GalleryRefreshReason.Poll -> state
        GalleryRefreshReason.Action ->
            state.copy(
                loading = false,
                errorText = "Action failed: ${message ?: "unknown error"}",
            )
        GalleryRefreshReason.Initial,
        GalleryRefreshReason.Category,
        GalleryRefreshReason.Manual,
        ->
            state.copy(
                loading = false,
                errorText = "Couldn't load images: ${message ?: "unknown error"}",
            )
    }

internal fun throwIfCancellation(error: Throwable) {
    if (error is CancellationException) {
        throw error
    }
}
