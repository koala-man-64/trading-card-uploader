package com.tradingcards.uploader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BlobUploaderTest {
    @Test
    fun uploadHeadersAddStorageApiVersionWhenIssuerOmitsIt() {
        val headers =
            blobUploadHeaders(
                mapOf(
                    "x-ms-blob-type" to "BlockBlob",
                    "Content-Type" to "image/jpeg",
                ),
            )

        assertEquals(DEFAULT_STORAGE_API_VERSION, headers["x-ms-version"])
    }

    @Test
    fun uploadHeadersPreserveIssuerStorageApiVersion() {
        val headers = blobUploadHeaders(mapOf("X-Ms-Version" to "2021-08-06"))

        assertEquals("2021-08-06", headers["X-Ms-Version"])
        assertFalse(headers.containsKey("x-ms-version"))
    }

    @Test
    fun contentTypeUsesCaseInsensitiveHeaderName() {
        val contentType = blobContentType(mapOf("content-type" to "image/heic"))

        assertEquals("image/heic", contentType)
    }
}
