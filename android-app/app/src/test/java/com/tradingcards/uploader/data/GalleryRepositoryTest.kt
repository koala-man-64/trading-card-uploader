package com.tradingcards.uploader.data

import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.GalleryImageDeleteRequest
import com.tradingcards.uploader.model.GalleryImageDeleteResponse
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.model.GallerySourceActionRequest
import com.tradingcards.uploader.model.GallerySourceActionResponse
import com.tradingcards.uploader.model.SasRequest
import com.tradingcards.uploader.model.SasResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class GalleryRepositoryTest {
    @Test
    fun listMapsScannerConfigurationError() =
        runTest {
            val repository =
                GalleryRepository(
                    apiBaseUrl = "https://api.example.test/api/",
                    client = scannerNotConfiguredClient(),
                )

            val error =
                runCatching {
                    repository.list("token", GalleryCategory.Processed)
                }.exceptionOrNull()

            assertTrue(error is ScannerNotConfiguredException)
            assertEquals(
                "SCANNER_ADMIN_BASE_URL is required for scanner gallery operations",
                error?.message,
            )
        }

    @Test
    fun previewUrlResolverKeepsAbsoluteUrls() {
        val resolved =
            resolveGalleryPreviewUrl(
                apiBaseUrl = "https://api.example.test/api/",
                previewUrl = "https://storage.example.test/raw/a.jpg?sig=abc",
            )

        assertEquals("https://storage.example.test/raw/a.jpg?sig=abc", resolved)
    }

    @Test
    fun previewUrlResolverUsesApiHostForRelativeUrls() {
        val resolved =
            resolveGalleryPreviewUrl(
                apiBaseUrl = "https://api.example.test/api/",
                previewUrl = "/api/v1/admin/gallery/image?category=processed&name=processed%2Fa.jpg",
            )

        assertEquals(
            "https://api.example.test/api/v1/admin/gallery/image?category=processed&name=processed%2Fa.jpg",
            resolved,
        )
        assertTrue(shouldAttachGalleryBearer("https://api.example.test/api/", resolved))
    }

    @Test
    fun galleryImageEndpointUrlEncodesCategoryAndName() {
        val image =
            GalleryImage(
                category = GalleryCategory.Processed.wireValue,
                name = "processed/card one.jpg",
                sourceBlobName = "raw/card one.jpg",
                size = 1,
                lastModifiedUtc = null,
                previewUrl = "/api/v1/admin/gallery/image?category=processed&name=processed%2Fcard+one.jpg",
                canCascade = true,
            )

        val resolved = galleryImageEndpointUrl("https://api.example.test/api", image)

        assertEquals(
            "https://api.example.test/api/v1/admin/gallery/image?category=processed&name=processed%2Fcard+one.jpg",
            resolved,
        )
    }

    private fun scannerNotConfiguredClient(): SasIssuerClient =
        object : SasIssuerClient {
            override suspend fun healthz(): Response<Map<String, String>> = error("healthz was not expected")

            override suspend fun issueUploadSas(
                authorization: String,
                request: SasRequest,
            ): Response<SasResponse> = error("issueUploadSas was not expected")

            override suspend fun listGalleryImages(
                authorization: String,
                category: String,
                limit: Int,
                cursor: String?,
            ): Response<GalleryImagesResponse> {
                val responseBody =
                    """
                    {
                      "error": "$SCANNER_NOT_CONFIGURED_CODE",
                      "message": "SCANNER_ADMIN_BASE_URL is required for scanner gallery operations"
                    }
                    """.trimIndent()
                return Response.error(
                    500,
                    responseBody.toResponseBody("application/json".toMediaType()),
                )
            }

            override suspend fun deleteGallerySourceGroup(
                authorization: String,
                request: GallerySourceActionRequest,
            ): Response<GallerySourceActionResponse> = error("deleteGallerySourceGroup was not expected")

            override suspend fun deleteGalleryImage(
                authorization: String,
                request: GalleryImageDeleteRequest,
            ): Response<GalleryImageDeleteResponse> = error("deleteGalleryImage was not expected")

            override suspend fun reprocessGallerySource(
                authorization: String,
                request: GallerySourceActionRequest,
            ): Response<GallerySourceActionResponse> = error("reprocessGallerySource was not expected")
        }
}
