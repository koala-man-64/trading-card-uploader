package com.tradingcards.uploader.ui

import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryImageNavigationTest {
    @Test
    fun nextImageReturnsFollowingItem() {
        val images = listOf(image("raw/a.jpg"), image("raw/b.jpg"), image("raw/c.jpg"))

        val next =
            adjacentGalleryImage(
                images,
                "raw/b.jpg",
                GalleryImageNavigationDirection.Next,
            )

        assertEquals("raw/c.jpg", next?.name)
    }

    @Test
    fun previousImageReturnsPriorItem() {
        val images = listOf(image("raw/a.jpg"), image("raw/b.jpg"), image("raw/c.jpg"))

        val previous =
            adjacentGalleryImage(
                images,
                "raw/b.jpg",
                GalleryImageNavigationDirection.Previous,
            )

        assertEquals("raw/a.jpg", previous?.name)
    }

    @Test
    fun boundariesDoNotWrap() {
        val images = listOf(image("raw/a.jpg"), image("raw/b.jpg"))

        assertNull(
            adjacentGalleryImage(
                images,
                "raw/a.jpg",
                GalleryImageNavigationDirection.Previous,
            ),
        )
        assertNull(
            adjacentGalleryImage(
                images,
                "raw/b.jpg",
                GalleryImageNavigationDirection.Next,
            ),
        )
    }

    @Test
    fun missingCurrentImageReturnsNull() {
        val images = listOf(image("raw/a.jpg"), image("raw/b.jpg"))

        val next =
            adjacentGalleryImage(
                images,
                "raw/missing.jpg",
                GalleryImageNavigationDirection.Next,
            )

        assertNull(next)
    }

    private fun image(name: String) =
        GalleryImage(
            category = GalleryCategory.Raw.wireValue,
            name = name,
            sourceBlobName = name,
            size = 1,
            lastModifiedUtc = null,
            previewUrl = "https://storage.example.test/$name?sig=test",
            canCascade = true,
        )
}
