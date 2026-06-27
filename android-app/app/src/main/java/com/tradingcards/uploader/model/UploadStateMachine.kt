package com.tradingcards.uploader.model

object UploadStateMachine {
    private val retryable = setOf(408, 429, 500, 502, 503, 504)

    fun nextForHttpFailure(statusCode: Int, attempts: Int, maxAttempts: Int): UploadStatus {
        if (statusCode in retryable && attempts < maxAttempts) {
            return UploadStatus.RetryWaiting
        }
        return UploadStatus.FailedTerminal
    }

    fun nextForNetworkFailure(attempts: Int, maxAttempts: Int): UploadStatus =
        if (attempts < maxAttempts) UploadStatus.RetryWaiting else UploadStatus.FailedTerminal

    fun assertTransition(from: UploadStatus, to: UploadStatus) {
        val allowed = when (from) {
            UploadStatus.Captured -> setOf(UploadStatus.Queued)
            UploadStatus.Queued -> setOf(UploadStatus.RequestingSas, UploadStatus.FailedTerminal)
            UploadStatus.RequestingSas -> setOf(UploadStatus.Uploading, UploadStatus.RetryWaiting, UploadStatus.FailedTerminal)
            UploadStatus.Uploading -> setOf(UploadStatus.Uploaded, UploadStatus.RetryWaiting, UploadStatus.FailedTerminal)
            UploadStatus.Uploaded -> setOf(UploadStatus.Complete)
            UploadStatus.RetryWaiting -> setOf(UploadStatus.RequestingSas, UploadStatus.FailedTerminal)
            UploadStatus.Complete,
            UploadStatus.FailedTerminal,
            -> emptySet()
        }
        check(to in allowed) { "Invalid upload transition: $from -> $to" }
    }
}
