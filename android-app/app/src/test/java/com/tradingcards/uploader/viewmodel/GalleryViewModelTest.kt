package com.tradingcards.uploader.viewmodel

import android.app.Activity
import com.tradingcards.uploader.auth.GalleryAuthTokenProvider
import com.tradingcards.uploader.data.GalleryRepository
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
import com.tradingcards.uploader.model.ScannerStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    @Test
    fun rapidCategorySelectionUpdatesSelectedTabImmediatelyAndLoadsLatestCategory() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val client = DelayedGalleryClient(responseDelayMs = 1_000)
                val viewModel =
                    GalleryViewModel(
                        repository =
                            GalleryRepository(
                                apiBaseUrl = "https://api.example.test/api/",
                                client = client,
                            ),
                        authRepository = StaticGalleryTokenProvider(),
                    )
                viewModel.onCategorySelected(category = GalleryCategory.Processed)
                runCurrent()

                assertEquals(GalleryCategory.Processed, viewModel.state.value.category)
                assertTrue(viewModel.state.value.loading)
                assertEquals(listOf(GalleryCategory.Processed.wireValue), client.requestedCategories)

                viewModel.onCategorySelected(category = GalleryCategory.Segmented)
                runCurrent()

                assertEquals(GalleryCategory.Segmented, viewModel.state.value.category)
                assertTrue(viewModel.state.value.loading)
                assertTrue(viewModel.state.value.items.isEmpty())
                assertEquals(listOf(GalleryCategory.Processed.wireValue), client.requestedCategories)

                advanceTimeBy(1_000)
                runCurrent()

                assertEquals(GalleryCategory.Segmented, viewModel.state.value.category)
                assertTrue(viewModel.state.value.loading)
                assertTrue(viewModel.state.value.items.isEmpty())
                assertEquals(
                    listOf(GalleryCategory.Processed.wireValue, GalleryCategory.Segmented.wireValue),
                    client.requestedCategories,
                )

                advanceTimeBy(1_000)
                runCurrent()

                assertEquals(GalleryCategory.Segmented, viewModel.state.value.category)
                assertFalse(viewModel.state.value.loading)
                assertEquals(listOf("segmented/card.jpg"), viewModel.state.value.items.map { it.name })
            } finally {
                Dispatchers.resetMain()
            }
        }

    private class StaticGalleryTokenProvider : GalleryAuthTokenProvider {
        override suspend fun acquireGalleryManageToken(activity: Activity): String = "token"

        override suspend fun acquireGalleryManageTokenSilent(): String = "token"
    }

    private class DelayedGalleryClient(
        private val responseDelayMs: Long,
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
            delay(responseDelayMs)
            return Response.success(
                GalleryImagesResponse(
                    category = category,
                    items = listOf(image(category)),
                    nextCursor = null,
                ),
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

        override suspend fun scannerStatus(authorization: String): Response<ScannerStatusResponse> {
            error("scannerStatus was not expected")
        }

        private fun image(category: String): GalleryImage =
            GalleryImage(
                category = category,
                name = "$category/card.jpg",
                sourceBlobName = "raw/card.jpg",
                size = 1,
                lastModifiedUtc = "2026-07-04T00:00:00Z",
                previewUrl = "/api/v1/admin/gallery/image?category=$category&name=$category%2Fcard.jpg",
                canCascade = true,
            )
    }
}
