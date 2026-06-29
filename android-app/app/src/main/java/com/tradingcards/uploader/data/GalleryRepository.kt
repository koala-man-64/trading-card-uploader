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
import org.json.JSONObject
import retrofit2.Response
import java.net.URI

internal const val SCANNER_NOT_CONFIGURED_CODE = "scanner_not_configured"

class ScannerNotConfiguredException(message: String) : IllegalStateException(message)

private data class GalleryErrorDetail(
    val code: String?,
    val message: String?,
) {
    val displayText: String?
        get() =
            listOfNotNull(
                code?.takeIf { it.isNotBlank() },
                message?.takeIf { it.isNotBlank() },
            ).joinToString(": ").takeIf { it.isNotBlank() }
}

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
            val detail = response.errorBody()?.string()?.let(::galleryErrorDetail)
            if (detail?.code == SCANNER_NOT_CONFIGURED_CODE) {
                throw ScannerNotConfiguredException(
                    detail.message ?: "Scanner gallery is not configured",
                )
            }
            error(
                listOfNotNull(
                    "Gallery request failed: HTTP ${response.code()}",
                    detail?.displayText,
                ).joinToString(": "),
            )
        }
        return response.body() ?: error("Gallery request returned an empty body")
    }

    private fun galleryErrorDetail(body: String): GalleryErrorDetail? =
        runCatching {
            val json = JSONObject(body)
            GalleryErrorDetail(
                code = json.optString("error").takeIf { it.isNotBlank() },
                message = json.optString("message").takeIf { it.isNotBlank() },
            ).takeIf { it.displayText != null }
        }.getOrNull()
}
