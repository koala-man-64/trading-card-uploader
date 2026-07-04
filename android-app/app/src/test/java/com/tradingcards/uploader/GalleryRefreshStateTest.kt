package com.tradingcards.uploader

import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.ui.GalleryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                errorText = "Couldn't load images: earlier failure",
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
        assertNull(next.errorText)
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
                errorText = "Couldn't load images: earlier failure",
            )

        val next = galleryStateForRefreshStart(state, GalleryCategory.Processed, GalleryRefreshReason.Manual)

        assertEquals(GalleryCategory.Processed, next.category)
        assertTrue(next.selectedNames.isEmpty())
        assertTrue(next.loading)
        assertNull(next.errorText)
    }

    @Test
    fun categoryRefreshStartClearsStaleItemsWithoutCache() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Raw,
                items = listOf(image("raw/a.jpg")),
                selectedNames = setOf("raw/a.jpg"),
                nextCursor = "next-raw",
            )

        val next = galleryStateForRefreshStart(state, GalleryCategory.Processed, GalleryRefreshReason.Category)

        assertEquals(GalleryCategory.Processed, next.category)
        assertTrue(next.items.isEmpty())
        assertNull(next.nextCursor)
        assertTrue(next.selectedNames.isEmpty())
        assertTrue(next.loading)
    }

    @Test
    fun categoryRefreshStartShowsCachedItemsWhileRefreshing() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Processed,
                items = listOf(image("processed/old.jpg")),
            )
        val cached =
            GallerySnapshot(
                items = listOf(image("raw/cached.jpg")),
                nextCursor = "next-raw",
            )

        val next =
            galleryStateForRefreshStart(
                state = state,
                category = GalleryCategory.Raw,
                reason = GalleryRefreshReason.Category,
                cachedSnapshot = cached,
            )

        assertEquals(GalleryCategory.Raw, next.category)
        assertEquals(listOf("raw/cached.jpg"), next.items.map { it.name })
        assertEquals("next-raw", next.nextCursor)
        assertTrue(next.loading)
    }

    @Test
    fun loadMoreAppendsNextPageAndDeduplicatesOverlap() {
        val state =
            GalleryUiState(
                category = GalleryCategory.Processed,
                items = listOf(image("processed/a_0.jpg"), image("processed/b_0.jpg")),
                nextCursor = "2",
                loadingMore = true,
            )
        val page =
            GalleryImagesResponse(
                category = GalleryCategory.Processed.wireValue,
                items = listOf(image("processed/b_0.jpg"), image("processed/c_0.jpg")),
                nextCursor = null,
            )

        val next = galleryStateForLoadMoreSuccess(state, page)

        assertEquals(
            listOf("processed/a_0.jpg", "processed/b_0.jpg", "processed/c_0.jpg"),
            next.items.map { it.name },
        )
        assertNull(next.nextCursor)
        assertFalse(next.loadingMore)
    }

    @Test
    fun loadMoreFailureKeepsLoadedItemsAndSurfacesError() {
        val state =
            GalleryUiState(
                items = listOf(image("raw/a.jpg")),
                nextCursor = "1",
                loadingMore = true,
            )

        val next = galleryStateForLoadMoreFailure(state, "boom")

        assertEquals(listOf("raw/a.jpg"), next.items.map { it.name })
        assertEquals("1", next.nextCursor)
        assertFalse(next.loadingMore)
        assertEquals("Couldn't load more images: boom", next.errorText)
    }

    @Test
    fun refreshFailureSurfacesErrorAndStopsLoading() {
        val state = GalleryUiState(loading = true)

        val next = galleryStateForRefreshFailure(state, GalleryRefreshReason.Manual, "boom")

        assertFalse(next.loading)
        assertEquals("Couldn't load images: boom", next.errorText)
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
            items = images.toList(),
            nextCursor = null,
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
