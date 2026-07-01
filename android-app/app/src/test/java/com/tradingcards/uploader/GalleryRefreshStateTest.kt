package com.tradingcards.uploader

import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.ui.GalleryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRefreshStateTest {
    @Test
    fun pollRefreshAppendsNewImagesWithoutClearingValidSelection() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Raw,
                items = listOf(image("raw/a.jpg")),
                selectedNames = setOf("raw/a.jpg"),
                statusText = "1 image(s)",
                accessToken = "old-token",
            )
        val loaded =
            loadedGallery(
                image("raw/a.jpg"),
                image("raw/b.jpg"),
            )

        val next = galleryStateForRefreshSuccess(state, "new-token", loaded, GalleryRefreshReason.Poll)

        assertEquals(listOf("raw/a.jpg", "raw/b.jpg"), next.items.map { it.name })
        assertEquals(setOf("raw/a.jpg"), next.selectedNames)
        assertEquals("2 image(s)", next.statusText)
        assertEquals("new-token", next.accessToken)
        assertFalse(next.loading)
    }

    @Test
    fun pollRefreshRemovesSelectionForDeletedImages() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Raw,
                items = listOf(image("raw/a.jpg"), image("raw/b.jpg")),
                selectedNames = setOf("raw/a.jpg", "raw/b.jpg"),
                accessToken = "token",
            )
        val loaded = loadedGallery(image("raw/b.jpg"))

        val next = galleryStateForRefreshSuccess(state, "token", loaded, GalleryRefreshReason.Poll)

        assertEquals(listOf("raw/b.jpg"), next.items.map { it.name })
        assertEquals(setOf("raw/b.jpg"), next.selectedNames)
    }

    @Test
    fun manualRefreshStartClearsSelectionAndShowsLoading() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Raw,
                items = listOf(image("raw/a.jpg")),
                selectedNames = setOf("raw/a.jpg"),
            )

        val next = galleryStateForRefreshStart(state, GalleryCategory.Processed, GalleryRefreshReason.Manual)

        assertEquals(GalleryCategory.Processed, next.category)
        assertTrue(next.selectedNames.isEmpty())
        assertTrue(next.loading)
        assertEquals("Loading processed images", next.statusText)
    }

    @Test
    fun categoryRefreshSuccessClearsSelection() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Raw,
                items = listOf(image("raw/a.jpg")),
                selectedNames = setOf("raw/a.jpg"),
            )
        val loaded = loadedGallery(image("raw/b.jpg"))

        val next = galleryStateForRefreshSuccess(state, "token", loaded, GalleryRefreshReason.Category)

        assertTrue(next.selectedNames.isEmpty())
        assertEquals(listOf("raw/b.jpg"), next.items.map { it.name })
        assertFalse(next.loading)
    }

    private fun loadedGallery(vararg images: GalleryImage): LoadedGallery =
        LoadedGallery(
            selectedCategory = GalleryCategory.Raw,
            response =
                GalleryImagesResponse(
                    category = GalleryCategory.Raw.wireValue,
                    items = images.toList(),
                    nextCursor = null,
                ),
        )

    private fun image(name: String): GalleryImage =
        GalleryImage(
            category = GalleryCategory.Raw.wireValue,
            name = name,
            sourceBlobName = name,
            size = 1,
            lastModifiedUtc = "2026-06-29T00:00:00Z",
            previewUrl = "https://storage.example.test/$name?sig=test",
            canCascade = true,
        )
}
