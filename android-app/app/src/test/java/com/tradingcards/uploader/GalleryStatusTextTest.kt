package com.tradingcards.uploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GalleryStatusTextTest {
    @Test
    fun scannerFallbackShowsRawPreviewCopy() {
        val statusText = galleryStatusText(itemCount = 3, scannerFallback = true)

        assertEquals("Showing 3 raw image(s)", statusText)
        assertFalse(statusText.contains("scanner", ignoreCase = true))
        assertFalse(statusText.contains("configured", ignoreCase = true))
    }

    @Test
    fun normalGalleryLoadShowsImageCount() {
        assertEquals("2 image(s)", galleryStatusText(itemCount = 2, scannerFallback = false))
    }
}
