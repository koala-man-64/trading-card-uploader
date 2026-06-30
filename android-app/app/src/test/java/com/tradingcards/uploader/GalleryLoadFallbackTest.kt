package com.tradingcards.uploader

import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.data.SCANNER_NOT_CONFIGURED_CODE
import com.tradingcards.uploader.data.SasIssuerClient
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

class GalleryLoadFallbackTest {
    @Test
    fun scannerFallbackKeepsRequestedCategorySelected() =
        runTest {
            val repository =
                GalleryRepository(
                    apiBaseUrl = "https://api.example.test/api/",
                    client = scannerNotConfiguredForProcessedClient(),
                )

            val loaded = loadGalleryWithRawFallback("token", GalleryCategory.Processed, repository)

            assertEquals(GalleryCategory.Processed, loaded.selectedCategory)
            assertEquals(GalleryCategory.Raw.wireValue, loaded.response.category)
            assertEquals(listOf("raw/a.jpg"), loaded.response.items.map { it.name })
            assertTrue(loaded.scannerFallback)
        }

    @Test
    fun scannerListFailureFallsBackToRawGallery() =
        runTest {
            val repository =
                GalleryRepository(
                    apiBaseUrl = "https://api.example.test/api/",
                    client = scannerListFailureForProcessedClient(),
                )

            val loaded = loadGalleryWithRawFallback("token", GalleryCategory.Processed, repository)

            assertEquals(GalleryCategory.Processed, loaded.selectedCategory)
            assertEquals(GalleryCategory.Raw.wireValue, loaded.response.category)
            assertEquals(listOf("raw/a.jpg"), loaded.response.items.map { it.name })
            assertTrue(loaded.scannerFallback)
        }

    private fun scannerNotConfiguredForProcessedClient(): SasIssuerClient =
        fallbackClient(
            """
            {
              "error": "$SCANNER_NOT_CONFIGURED_CODE",
              "message": "SCANNER_ADMIN_BASE_URL is required for scanner gallery operations"
            }
            """.trimIndent(),
        )

    private fun scannerListFailureForProcessedClient(): SasIssuerClient =
        fallbackClient(
            """
            {
              "error": "gallery_list_failed",
              "message": "Gallery images could not be listed"
            }
            """.trimIndent(),
        )

    private fun fallbackClient(processedErrorBody: String): SasIssuerClient =
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
            ): Response<GalleryImagesResponse> =
                if (category == GalleryCategory.Raw.wireValue) {
                    Response.success(
                        GalleryImagesResponse(
                            category = GalleryCategory.Raw.wireValue,
                            items =
                                listOf(
                                    GalleryImage(
                                        category = GalleryCategory.Raw.wireValue,
                                        name = "raw/a.jpg",
                                        sourceBlobName = "raw/a.jpg",
                                        size = 1,
                                        lastModifiedUtc = null,
                                        previewUrl = "https://storage.example.test/raw/a.jpg?sas=1",
                                        canCascade = true,
                                    ),
                                ),
                            nextCursor = null,
                        ),
                    )
                } else {
                    Response.error(
                        500,
                        processedErrorBody.toResponseBody("application/json".toMediaType()),
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
