package com.tradingcards.uploader.ui

import com.tradingcards.uploader.model.GalleryImage

internal enum class GalleryImageNavigationDirection {
    Previous,
    Next,
}

internal fun adjacentGalleryImage(
    images: List<GalleryImage>,
    currentName: String,
    direction: GalleryImageNavigationDirection,
): GalleryImage? {
    val currentIndex = images.indexOfFirst { it.name == currentName }
    if (currentIndex == -1) {
        return null
    }
    val targetIndex =
        when (direction) {
            GalleryImageNavigationDirection.Previous -> currentIndex - 1
            GalleryImageNavigationDirection.Next -> currentIndex + 1
        }
    return images.getOrNull(targetIndex)
}
