package com.tradingcards.uploader.ui

import com.tradingcards.uploader.model.UploadEntity
import com.tradingcards.uploader.model.UploadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class StatusTone { Accent, Success, Neutral, Error }

internal data class UploadStatusUi(
    val label: String,
    val tone: StatusTone,
    val inProgress: Boolean,
    val canRetry: Boolean,
)

internal fun UploadStatus.toUi(): UploadStatusUi =
    when (this) {
        UploadStatus.Captured,
        UploadStatus.Queued,
        ->
            UploadStatusUi(
                label = "Waiting to upload",
                tone = StatusTone.Neutral,
                inProgress = false,
                canRetry = false,
            )

        UploadStatus.RequestingSas,
        UploadStatus.Uploading,
        UploadStatus.Uploaded,
        ->
            UploadStatusUi(
                label = "Uploading",
                tone = StatusTone.Accent,
                inProgress = true,
                canRetry = false,
            )

        UploadStatus.RetryWaiting ->
            UploadStatusUi(
                label = "Retrying",
                tone = StatusTone.Accent,
                inProgress = true,
                canRetry = false,
            )

        UploadStatus.Complete ->
            UploadStatusUi(
                label = "Uploaded",
                tone = StatusTone.Success,
                inProgress = false,
                canRetry = false,
            )

        UploadStatus.FailedTerminal ->
            UploadStatusUi(
                label = "Upload failed",
                tone = StatusTone.Error,
                inProgress = false,
                canRetry = true,
            )
    }

private val timeFormatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

internal fun rowSubtitle(
    upload: UploadEntity,
    ui: UploadStatusUi,
): String =
    when (ui.tone) {
        StatusTone.Error -> "Tap retry to upload again"
        StatusTone.Neutral -> ui.label
        else -> timeFormatter.format(Date(upload.updatedAtEpochMillis))
    }
