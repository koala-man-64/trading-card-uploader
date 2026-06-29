package com.tradingcards.uploader.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryModelsTest {
    @Test
    fun categoryWireValuesMatchApiContract() {
        assertEquals("raw", GalleryCategory.Raw.wireValue)
        assertEquals("processed", GalleryCategory.Processed.wireValue)
        assertEquals("segmented", GalleryCategory.Segmented.wireValue)
    }

    @Test
    fun selectedSourceNamesDeduplicateBySourceBlobName() {
        val items =
            listOf(
                image("processed/a_1.jpg", "raw/a.jpg"),
                image("processed/a_2.jpg", "raw/a.jpg"),
                image("processed/legacy.jpg", null),
            )

        val selected =
            selectedGallerySourceNames(
                items,
                setOf("processed/a_1.jpg", "processed/a_2.jpg", "processed/legacy.jpg"),
            )

        assertEquals(listOf("raw/a.jpg"), selected)
    }

    @Test
    fun selectedRawImagesUseTheirOwnBlobName() {
        val items =
            listOf(
                image(
                    name = "raw/a.jpg",
                    source = null,
                    category = GalleryCategory.Raw.wireValue,
                ),
            )

        val selected = selectedGallerySourceNames(items, setOf("raw/a.jpg"))

        assertEquals(listOf("raw/a.jpg"), selected)
    }

    @Test
    fun selectedIndividualDeleteImagesIncludeOnlyLegacyOutputs() {
        val items =
            listOf(
                image("processed/a_1.jpg", "raw/a.jpg"),
                image("processed/legacy.jpg", null),
                image(
                    name = "raw/source.jpg",
                    source = null,
                    category = GalleryCategory.Raw.wireValue,
                ),
            )

        val selected =
            selectedGalleryIndividualDeleteImages(
                items,
                setOf("processed/a_1.jpg", "processed/legacy.jpg", "raw/source.jpg"),
            )

        assertEquals(listOf("processed/legacy.jpg"), selected.map { it.name })
    }

    private fun image(
        name: String,
        source: String?,
        category: String = GalleryCategory.Processed.wireValue,
    ) = GalleryImage(
        category = category,
        name = name,
        sourceBlobName = source,
        size = 1,
        lastModifiedUtc = null,
        previewUrl = "/api/v1/admin/gallery/image?name=$name",
        canCascade = source != null,
    )
}
