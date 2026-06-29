package com.tradingcards.uploader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val SCANNER_NOT_CONFIGURED_CODE = "scanner_not_configured"

private val galleryErrorJsonAdapter =
    Moshi
        .Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(GalleryErrorBody::class.java)

class ScannerNotConfiguredException(message: String) : IllegalStateException(message)

private data class GalleryErrorBody(
    val error: String? = null,
    val message: String? = null,
)

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
            val urls =
                listOfNotNull(
                    runCatching { resolveGalleryPreviewUrl(apiBaseUrl, image.previewUrl) }.getOrNull(),
                    runCatching { galleryImageEndpointUrl(apiBaseUrl, image) }.getOrNull(),
                ).distinct()
            for (url in urls) {
                runCatching { loadPreviewBitmap(accessToken, url) }
                    .getOrNull()
                    ?.let { return@withContext it }
            }
            null
        }

    private fun loadPreviewBitmap(
        accessToken: String,
        url: String,
    ): Bitmap? {
        val requestBuilder = Request.Builder().url(url)
        if (shouldAttachGalleryBearer(apiBaseUrl, url)) {
            requestBuilder.header("Authorization", bearer(accessToken))
        }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            return response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
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
            galleryErrorJsonAdapter.fromJson(body)?.let { parsed ->
                GalleryErrorDetail(
                    code = parsed.error?.takeIf { it.isNotBlank() },
                    message = parsed.message?.takeIf { it.isNotBlank() },
                ).takeIf { it.displayText != null }
            }
        }.getOrNull()
}

internal fun resolveGalleryPreviewUrl(
    apiBaseUrl: String,
    previewUrl: String,
): String {
    val preview = URI(previewUrl)
    if (preview.isAbsolute) {
        return preview.toString()
    }
    return apiBaseUri(apiBaseUrl).resolve(previewUrl).toString()
}

internal fun galleryImageEndpointUrl(
    apiBaseUrl: String,
    image: GalleryImage,
): String =
    apiBaseUri(apiBaseUrl)
        .resolve(
            "v1/admin/gallery/image?category=${queryValue(image.category)}&name=${queryValue(image.name)}",
        )
        .toString()

internal fun shouldAttachGalleryBearer(
    apiBaseUrl: String,
    url: String,
): Boolean {
    val apiUri = apiBaseUri(apiBaseUrl)
    val previewUri = URI(url)
    return apiUri.host.equals(previewUri.host, ignoreCase = true)
}

private fun apiBaseUri(apiBaseUrl: String): URI {
    val normalized = apiBaseUrl.takeIf { it.endsWith("/") } ?: "$apiBaseUrl/"
    return URI(normalized)
}

private fun queryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
