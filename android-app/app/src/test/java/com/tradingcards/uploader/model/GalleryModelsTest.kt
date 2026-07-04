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
    fun cardDisplayNameUsesScannerMetadataOnly() {
        assertEquals(
            "Pikachu",
            image(
                name = "processed/raw-abc/machine_0.jpg",
                source = "raw/a.jpg",
                cardName = "  Pikachu  ",
            ).cardDisplayName(),
        )
        assertNull(
            image(
                name = "processed/raw-abc/Pikachu_0.jpg",
                source = "raw/a.jpg",
                cardName = null,
            ).cardDisplayName(),
        )
    }

    @Test
    fun cardPriceTextNormalizesScannerMetadata() {
        assertEquals("$12.50", image("processed/a.jpg", "raw/a.jpg", price = "  $12.50  ").cardPriceText())
        assertNull(image("processed/a.jpg", "raw/a.jpg", price = " ").cardPriceText())
    }

    @Test
    fun groupedGalleryImagesByCardNameGroupsNormalizedNamesWithUnknownsLast() {
        val groups =
            groupedGalleryImagesByCardName(
                listOf(
                    image("processed/2.jpg", "raw/b.jpg", cardName = null),
                    image("processed/1.jpg", "raw/a.jpg", cardName = "Pikachu", price = "$1.00"),
                    image("processed/3.jpg", "raw/c.jpg", cardName = "  pikachu  ", price = "$1.00"),
                    image("processed/4.jpg", "raw/d.jpg", cardName = "Charizard", price = "$10.00"),
                ),
            )

        assertEquals(listOf("Charizard", "Pikachu", null), groups.map { it.cardName })
        assertEquals(listOf("processed/1.jpg", "processed/3.jpg"), groups[1].items.map { it.name })
        assertEquals("$1.00", groups[1].priceSummary)
        assertNull(groups[2].priceSummary)
    }

    @Test
    fun groupedGalleryImagesByCardNameSummarizesMultiplePrices() {
        val groups =
            groupedGalleryImagesByCardName(
                listOf(
                    image("processed/1.jpg", "raw/a.jpg", cardName = "Pikachu", price = "$1.00"),
                    image("processed/2.jpg", "raw/b.jpg", cardName = "pikachu", price = "$2.00"),
                ),
            )

        assertEquals(MULTIPLE_PRICES_SUMMARY, groups.single().priceSummary)
    }

    private fun image(
        name: String,
        source: String?,
        category: String = GalleryCategory.Processed.wireValue,
        cardName: String? = null,
        price: String? = null,
    ) = GalleryImage(
        category = category,
        name = name,
        sourceBlobName = source,
        size = 1,
        lastModifiedUtc = null,
        previewUrl = "/api/v1/admin/gallery/image?name=$name",
        canCascade = source != null,
        cardName = cardName,
        price = price,
    )
}
