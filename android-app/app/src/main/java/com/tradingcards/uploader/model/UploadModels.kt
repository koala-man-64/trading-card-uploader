package com.tradingcards.uploader.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class UploadStatus {
    Captured,
    Queued,
    RequestingSas,
    Uploading,
    Uploaded,
    Complete,
    RetryWaiting,
    FailedTerminal,
}

@Entity(tableName = "upload_queue")
data class UploadEntity(
    @PrimaryKey val uploadId: String,
    val localUri: String,
    val contentType: String,
    val contentLengthBytes: Long,
    val sha256Hex: String?,
    val serverUploadId: String?,
    val blobName: String?,
    val status: UploadStatus,
    val attemptCount: Int,
    val lastError: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class SasRequest(
    val clientUploadId: String,
    val contentType: String,
    val contentLengthBytes: Long,
    val sha256Hex: String?,
)

data class SasResponse(
    val uploadId: String,
    val blobName: String,
    val uploadUrl: String,
    val expiresAtUtc: String,
    val requiredHeaders: Map<String, String>,
    val maxContentLengthBytes: Long,
)

enum class GalleryCategory(val wireValue: String) {
    Raw("raw"),
    Processed("processed"),
    Segmented("segmented"),
}

data class GalleryImage(
    val category: String,
    val name: String,
    val sourceBlobName: String?,
    val size: Long,
    val lastModifiedUtc: String?,
    val previewUrl: String,
    val canCascade: Boolean,
    val cardName: String? = null,
    val price: String? = null,
)

data class GalleryImagesResponse(
    val category: String,
    val items: List<GalleryImage>,
    val nextCursor: String?,
)

data class GalleryCardGroup(
    val cardName: String?,
    val items: List<GalleryImage>,
    val priceSummary: String?,
)

const val MULTIPLE_PRICES_SUMMARY = "Multiple prices"

private val WHITESPACE_REGEX = Regex("\\s+")

fun GalleryImage.cardDisplayName(): String? = normalizedMetadataText(cardName)

fun GalleryImage.cardPriceText(): String? = normalizedMetadataText(price)

fun groupedGalleryImagesByCardName(items: List<GalleryImage>): List<GalleryCardGroup> =
    items
        .groupBy { image -> image.cardDisplayName()?.lowercase() }
        .map { (_, groupItems) ->
            val sortedItems = groupItems.sortedBy { it.name }
            val displayName = sortedItems.firstNotNullOfOrNull { it.cardDisplayName() }
            GalleryCardGroup(
                cardName = displayName,
                items = sortedItems,
                priceSummary = priceSummary(sortedItems),
            )
        }.sortedWith(
            compareBy<GalleryCardGroup> { it.cardName == null }
                .thenBy { it.cardName?.lowercase().orEmpty() },
        )

private fun priceSummary(items: List<GalleryImage>): String? {
    val prices = items.mapNotNull { it.cardPriceText() }.distinct()
    return when (prices.size) {
        0 -> null
        1 -> prices.single()
        else -> MULTIPLE_PRICES_SUMMARY
    }
}

private fun normalizedMetadataText(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { WHITESPACE_REGEX.replace(it, " ") }

data class GallerySourceActionRequest(
    val sourceBlobName: String,
)

data class GallerySourceActionResponse(
    val sourceBlobName: String,
)

data class GalleryImageDeleteRequest(
    val category: String,
    val name: String,
)

data class GalleryImageDeleteResponse(
    val category: String,
    val name: String,
    val deleted: Boolean,
)

fun selectedGallerySourceNames(
    items: List<GalleryImage>,
    selectedNames: Set<String>,
): List<String> =
    items
        .filter { it.name in selectedNames }
        .mapNotNull { image ->
            image.sourceBlobName
                ?: image.name.takeIf { image.category == GalleryCategory.Raw.wireValue }
        }
        .distinct()

fun selectedGalleryIndividualDeleteImages(
    items: List<GalleryImage>,
    selectedNames: Set<String>,
): List<GalleryImage> =
    items
        .filter { image ->
            image.name in selectedNames &&
                image.category != GalleryCategory.Raw.wireValue &&
                image.sourceBlobName == null
        }.distinctBy { it.name }

class UploadStatusConverter {
    @TypeConverter
    fun fromStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): UploadStatus = UploadStatus.valueOf(value)
}
