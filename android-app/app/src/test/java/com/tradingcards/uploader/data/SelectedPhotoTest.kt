package com.tradingcards.uploader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedPhotoTest {
    @Test
    fun supportsJpegAliases() {
        assertEquals(JPEG_CONTENT_TYPE, supportedSelectedPhotoContentType("image/jpeg"))
        assertEquals(JPEG_CONTENT_TYPE, supportedSelectedPhotoContentType(" image/JPG "))
    }

    @Test
    fun supportsHeic() {
        assertEquals(HEIC_CONTENT_TYPE, supportedSelectedPhotoContentType("image/heic"))
        assertEquals(HEIC_CONTENT_TYPE, supportedSelectedPhotoContentType(" image/HEIF "))
    }

    @Test
    fun rejectsUnsupportedImageTypes() {
        assertNull(supportedSelectedPhotoContentType("image/png"))
        assertNull(supportedSelectedPhotoContentType(null))
    }

    @Test
    fun mapsStorageExtensionFromContentType() {
        assertEquals(".jpg", selectedPhotoFileExtension(JPEG_CONTENT_TYPE))
        assertEquals(".heic", selectedPhotoFileExtension(HEIC_CONTENT_TYPE))
    }
}
