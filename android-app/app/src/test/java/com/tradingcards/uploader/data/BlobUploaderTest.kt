package com.tradingcards.uploader.data

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun includesAzureHeadersAndNormalizedBodyInFailureMessage() {
        val message =
            buildBlobFailureMessage(
                statusCode = 400,
                headers =
                    Headers.headersOf(
                        "x-ms-error-code",
                        "InvalidHeaderValue",
                        "x-ms-request-id",
                        "req-123",
                    ),
                bodyText = "  <Error>\n    <Message>Bad header</Message>\n  </Error>  ",
            )

        assertEquals(
            "Blob upload failed: 400 | azure=InvalidHeaderValue | requestId=req-123 | body=<Error> <Message>Bad header</Message> </Error>",
            message,
        )
    }

    @Test
    fun truncatesLongFailureBody() {
        val longBody = "x".repeat(400)

        val message =
            buildBlobFailureMessage(
                statusCode = 400,
                headers = Headers.headersOf(),
                bodyText = longBody,
            )

        assertTrue(message.startsWith("Blob upload failed: 400 | body="))
        assertTrue(message.endsWith("..."))
        assertTrue(message.length < 320)
    }
}
