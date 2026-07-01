package com.tradingcards.uploader

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryStatusTextTest {
    @Test
    fun normalGalleryLoadShowsImageCount() {
        assertEquals("2 image(s)", galleryStatusText(itemCount = 2))
    }
}
