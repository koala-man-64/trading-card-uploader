package com.tradingcards.uploader.model

object UploadStateMachine {
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_INTERNAL_SERVER_ERROR = 500
    private const val HTTP_BAD_GATEWAY = 502
    private const val HTTP_SERVICE_UNAVAILABLE = 503
    private const val HTTP_GATEWAY_TIMEOUT = 504

    private val retryable =
        setOf(
            HTTP_REQUEST_TIMEOUT,
            HTTP_TOO_MANY_REQUESTS,
            HTTP_INTERNAL_SERVER_ERROR,
            HTTP_BAD_GATEWAY,
            HTTP_SERVICE_UNAVAILABLE,
            HTTP_GATEWAY_TIMEOUT,
        )

    fun nextForHttpFailure(
        statusCode: Int,
        attempts: Int,
        maxAttempts: Int,
    ): UploadStatus {
        if (statusCode in retryable && attempts < maxAttempts) {
            return UploadStatus.RetryWaiting
        }
        return UploadStatus.FailedTerminal
    }

    fun nextForNetworkFailure(
        attempts: Int,
        maxAttempts: Int,
    ): UploadStatus {
        return if (attempts < maxAttempts) {
            UploadStatus.RetryWaiting
        } else {
            UploadStatus.FailedTerminal
        }
    }

    fun assertTransition(
        from: UploadStatus,
        to: UploadStatus,
    ) {
        val allowed =
            when (from) {
                UploadStatus.Captured ->
                    setOf(UploadStatus.Queued)
                UploadStatus.Queued ->
                    setOf(UploadStatus.RequestingSas, UploadStatus.FailedTerminal)
                UploadStatus.RequestingSas ->
                    setOf(
                        UploadStatus.Uploading,
                        UploadStatus.RetryWaiting,
                        UploadStatus.FailedTerminal,
                    )
                UploadStatus.Uploading ->
                    setOf(
                        UploadStatus.Uploaded,
                        UploadStatus.RetryWaiting,
                        UploadStatus.FailedTerminal,
                    )
                UploadStatus.Uploaded ->
                    setOf(UploadStatus.Complete)
                UploadStatus.RetryWaiting ->
                    setOf(UploadStatus.RequestingSas, UploadStatus.FailedTerminal)
                UploadStatus.Complete,
                UploadStatus.FailedTerminal,
                -> emptySet()
            }
        check(to in allowed) { "Invalid upload transition: $from -> $to" }
    }
}
