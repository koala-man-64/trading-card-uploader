package com.tradingcards.uploader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun cardDisplayNameRecoversOcrNameFromProcessedCropBlob() {
        assertEquals(
            "Pikachu",
            image("processed/raw-abc/Pikachu_0.jpg", "raw/a.jpg").cardDisplayName(),
        )
        assertEquals(
            "Dark Charizard Holo",
            image("processed/raw-abc/Dark_Charizard_Holo_12.jpg", "raw/a.jpg").cardDisplayName(),
        )
    }

    @Test
    fun cardDisplayNameIsNullForMachineGeneratedBlobNames() {
        assertNull(
            image(
                name = "raw/tenant/user/20260703/3f2a1b4c-9d8e-4f00-b111-222333444555.jpg",
                source = null,
                category = GalleryCategory.Raw.wireValue,
            ).cardDisplayName(),
        )
        assertNull(image("processed/raw-abc/1a2b3c4d5e6f7a8b9c0d_3.jpg", "raw/a.jpg").cardDisplayName())
        assertNull(image("processed/raw-abc/_0.jpg", "raw/a.jpg").cardDisplayName())
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
