package com.tradingcards.uploader.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadStateMachineTest {
    @Test
    fun retryableHttpFailuresRetryUntilMaxAttempts() {
        assertEquals(
            UploadStatus.RetryWaiting,
            UploadStateMachine.nextForHttpFailure(statusCode = 503, attempts = 1, maxAttempts = 5),
        )
    }

    @Test
    fun retryableHttpFailuresBecomeTerminalAtMaxAttempts() {
        assertEquals(
            UploadStatus.FailedTerminal,
            UploadStateMachine.nextForHttpFailure(statusCode = 503, attempts = 5, maxAttempts = 5),
        )
    }

    @Test
    fun validationFailuresAreTerminal() {
        assertEquals(
            UploadStatus.FailedTerminal,
            UploadStateMachine.nextForHttpFailure(statusCode = 400, attempts = 1, maxAttempts = 5),
        )
    }
}
