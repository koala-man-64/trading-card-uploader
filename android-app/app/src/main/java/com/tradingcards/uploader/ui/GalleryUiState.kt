package com.tradingcards.uploader.ui

import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage

data class GalleryUiState(
    val category: GalleryCategory = GalleryCategory.Raw,
    val items: List<GalleryImage> = emptyList(),
    val selectedNames: Set<String> = emptySet(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null,
    val errorText: String? = null,
    val accessToken: String? = null,
)
