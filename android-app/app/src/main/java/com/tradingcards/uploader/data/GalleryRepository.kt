package com.tradingcards.uploader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.GalleryImageDeleteRequest
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.model.GallerySourceActionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import java.net.URI

class GalleryRepository(
    private val apiBaseUrl: String,
    private val client: SasIssuerClient = SasIssuerClient.create(apiBaseUrl),
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun list(
        accessToken: String,
        category: GalleryCategory,
    ): GalleryImagesResponse =
        requireBody(
            client.listGalleryImages(
                authorization = bearer(accessToken),
                category = category.wireValue,
            ),
        )

    suspend fun deleteSourceGroup(
        accessToken: String,
        sourceBlobName: String,
    ) {
        requireBody(
            client.deleteGallerySourceGroup(
                authorization = bearer(accessToken),
                request = GallerySourceActionRequest(sourceBlobName),
            ),
        )
    }

    suspend fun deleteImage(
        accessToken: String,
        image: GalleryImage,
    ) {
        requireBody(
            client.deleteGalleryImage(
                authorization = bearer(accessToken),
                request = GalleryImageDeleteRequest(image.category, image.name),
            ),
        )
    }

    suspend fun reprocessSource(
        accessToken: String,
        sourceBlobName: String,
    ) {
        requireBody(
            client.reprocessGallerySource(
                authorization = bearer(accessToken),
                request = GallerySourceActionRequest(sourceBlobName),
            ),
        )
    }

    suspend fun loadPreview(
        accessToken: String,
        image: GalleryImage,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val url = resolvePreviewUrl(image.previewUrl)
            val requestBuilder = Request.Builder().url(url)
            if (shouldAttachBearer(url)) {
                requestBuilder.header("Authorization", bearer(accessToken))
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }
                response.body?.byteStream()?.use(BitmapFactory::decodeStream)
            }
        }

    private fun resolvePreviewUrl(previewUrl: String): String {
        val preview = URI(previewUrl)
        if (preview.isAbsolute) {
            return preview.toString()
        }
        return URI(apiBaseUrl).resolve(previewUrl).toString()
    }

    private fun shouldAttachBearer(url: String): Boolean {
        val apiUri = URI(apiBaseUrl)
        val previewUri = URI(url)
        return apiUri.host.equals(previewUri.host, ignoreCase = true)
    }

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    private fun <T> requireBody(response: Response<T>): T {
        if (!response.isSuccessful) {
            error("Gallery request failed: HTTP ${response.code()}")
        }
        return response.body() ?: error("Gallery request returned an empty body")
    }
}
