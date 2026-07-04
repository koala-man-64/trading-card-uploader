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
)

data class GalleryImagesResponse(
    val category: String,
    val items: List<GalleryImage>,
    val nextCursor: String?,
)

// Scanner crops are uploaded as {CardName}_{cropIndex}.jpg, where CardName
// comes from the scanner's on-device OCR pass over the top of each card.
private val CROP_INDEX_SUFFIX_REGEX = Regex("_\\d+$")

// Raw uploads and hash-derived blob names (UUIDs, sha256 stems) carry no
// human-meaningful text and must never leak into the UI.
private val MACHINE_NAME_REGEX = Regex("^[0-9a-fA-F-]{16,}$")

private val WORD_SEPARATOR_REGEX = Regex("[_\\s]+")

/**
 * Recovers the card name the scanner's OCR encoded into the blob filename
 * (`processed/{folder}/{CardName}_{idx}.jpg`), or null when the name is
 * machine-generated (raw upload UUIDs, hashes) and has nothing to show.
 */
fun GalleryImage.cardDisplayName(): String? {
    val stem = name.substringAfterLast('/').substringBeforeLast('.')
    val base = CROP_INDEX_SUFFIX_REGEX.replace(stem, "")
    if (base.isBlank() || MACHINE_NAME_REGEX.matches(base)) {
        return null
    }
    return WORD_SEPARATOR_REGEX
        .replace(base, " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}

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
