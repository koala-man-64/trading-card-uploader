package com.tradingcards.uploader

import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.data.SCANNER_NOT_CONFIGURED_CODE
import com.tradingcards.uploader.data.SasIssuerClient
import com.tradingcards.uploader.data.ScannerNotConfiguredException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class GalleryLoadTest {
    @Test
    fun processedGalleryLoadKeepsRequestedCategory() =
        runTest {
            val repository =
                GalleryRepository(
                    apiBaseUrl = "https://api.example.test/api/",
                    client = successForProcessedClient(),
                )

            val loaded = loadGallery("token", GalleryCategory.Processed, repository)

            assertEquals(GalleryCategory.Processed, loaded.selectedCategory)
            assertEquals(GalleryCategory.Processed.wireValue, loaded.response.category)
            assertEquals(listOf("processed/a.jpg"), loaded.response.items.map { it.name })
        }

    @Test
    fun scannerNotConfiguredFailureIsNotReplacedWithRawGallery() =
        runTest {
            val client = scannerNotConfiguredForProcessedClient()
            val repository =
                GalleryRepository(
                    apiBaseUrl = "https://api.example.test/api/",
                    client = client,
                )

            val error =
                runCatching {
                    loadGallery("token", GalleryCategory.Processed, repository)
                }.exceptionOrNull()

            assertTrue(error is ScannerNotConfiguredException)
            assertEquals(
                "SCANNER_ADMIN_BASE_URL is required for scanner gallery operations",
                error?.message,
            )
            assertFalse(client.requestedCategories.contains(GalleryCategory.Raw.wireValue))
        }

    @Test
    fun scannerListFailureIsNotReplacedWithRawGallery() =
        runTest {
            val client = scannerListFailureForProcessedClient()
            val repository =
                GalleryRepository(
                    apiBaseUrl = "https://api.example.test/api/",
                    client = client,
                )

            val error =
                runCatching {
                    loadGallery("token", GalleryCategory.Processed, repository)
                }.exceptionOrNull()

            assertEquals(
                "Gallery request failed: HTTP 500: gallery_list_failed: Gallery images could not be listed",
                error?.message,
            )
            assertFalse(client.requestedCategories.contains(GalleryCategory.Raw.wireValue))
        }

    private fun successForProcessedClient(): RecordingGalleryClient =
        RecordingGalleryClient(
            processedResponse =
                Response.success(
                    GalleryImagesResponse(
                        category = GalleryCategory.Processed.wireValue,
                        items =
                            listOf(
                                GalleryImage(
                                    category = GalleryCategory.Processed.wireValue,
                                    name = "processed/a.jpg",
                                    sourceBlobName = "raw/a.jpg",
                                    size = 1,
                                    lastModifiedUtc = null,
                                    previewUrl = PROCESSED_PREVIEW_URL,
                                    canCascade = true,
                                ),
                            ),
                        nextCursor = null,
                    ),
                ),
        )

    private fun scannerNotConfiguredForProcessedClient(): RecordingGalleryClient =
        RecordingGalleryClient(
            processedResponse =
                Response.error(
                    500,
                    """
                    {
                      "error": "$SCANNER_NOT_CONFIGURED_CODE",
                      "message": "SCANNER_ADMIN_BASE_URL is required for scanner gallery operations"
                    }
                    """.trimIndent().toResponseBody("application/json".toMediaType()),
                ),
        )

    private fun scannerListFailureForProcessedClient(): RecordingGalleryClient =
        RecordingGalleryClient(
            processedResponse =
                Response.error(
                    500,
                    """
                    {
                      "error": "gallery_list_failed",
                      "message": "Gallery images could not be listed"
                    }
                    """.trimIndent().toResponseBody("application/json".toMediaType()),
                ),
        )

    private class RecordingGalleryClient(
        private val processedResponse: Response<GalleryImagesResponse>,
    ) : SasIssuerClient {
        val requestedCategories = mutableListOf<String>()

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
            requestedCategories += category
            return if (category == GalleryCategory.Processed.wireValue) {
                processedResponse
            } else {
                error("Unexpected gallery category: $category")
            }
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

    private companion object {
        const val PROCESSED_PREVIEW_URL =
            "/api/v1/admin/gallery/image?category=processed&name=processed%2Fa.jpg"
    }
}
