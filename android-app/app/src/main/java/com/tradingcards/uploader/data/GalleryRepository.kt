package com.tradingcards.uploader.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.GalleryImageDeleteRequest
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.model.GallerySourceActionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * Loads a preview bitmap through Coil so repeated grid scrolls hit its
     * memory/disk cache instead of re-fetching and re-decoding at full
     * resolution every time. [targetSize] bounds the decode resolution
     * (e.g. a small size for grid thumbnails, larger for a full-screen
     * viewer) via [BitmapFactory] downsampling.
     */
    suspend fun loadPreview(
        context: Context,
        imageLoader: ImageLoader,
        accessToken: String,
        image: GalleryImage,
        targetSize: Size = Size.ORIGINAL,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val urls =
                listOfNotNull(
                    runCatching { resolveGalleryPreviewUrl(apiBaseUrl, image.previewUrl) }.getOrNull(),
                    runCatching { galleryImageEndpointUrl(apiBaseUrl, image) }.getOrNull(),
                ).distinct()
            for (url in urls) {
                runCatching { loadPreviewBitmap(context, imageLoader, accessToken, url, targetSize) }
                    .getOrNull()
                    ?.let { return@withContext it }
            }
            null
        }

    private suspend fun loadPreviewBitmap(
        context: Context,
        imageLoader: ImageLoader,
        accessToken: String,
        url: String,
        targetSize: Size,
    ): Bitmap? {
        val requestBuilder =
            ImageRequest.Builder(context)
                .data(url)
                .size(targetSize)
        if (shouldAttachGalleryBearer(apiBaseUrl, url)) {
            requestBuilder.addHeader("Authorization", bearer(accessToken))
        }
        val result = imageLoader.execute(requestBuilder.build())
        return (result as? SuccessResult)?.drawable?.toBitmap()
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
