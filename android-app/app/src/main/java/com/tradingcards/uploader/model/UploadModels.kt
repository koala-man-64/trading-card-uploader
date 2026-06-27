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

class UploadStatusConverter {
    @TypeConverter
    fun fromStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): UploadStatus = UploadStatus.valueOf(value)
}
