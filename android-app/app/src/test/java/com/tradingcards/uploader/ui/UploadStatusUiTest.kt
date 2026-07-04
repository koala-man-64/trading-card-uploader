package com.tradingcards.uploader.ui

import com.tradingcards.uploader.model.UploadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStatusUiTest {
    @Test
    fun pendingUploadsCanBeRemoved() {
        assertTrue(UploadStatus.Queued.toUi().canRemove)
        assertTrue(UploadStatus.RetryWaiting.toUi().canRemove)
        assertTrue(UploadStatus.FailedTerminal.toUi().canRemove)
    }

    @Test
    fun completedUploadsCannotBeRemoved() {
        assertFalse(UploadStatus.Complete.toUi().canRemove)
    }
}
