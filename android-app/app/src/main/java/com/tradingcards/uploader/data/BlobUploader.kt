package com.tradingcards.uploader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

internal const val DEFAULT_STORAGE_API_VERSION = "2021-08-06"
private const val MAX_FAILURE_BODY_CHARS = 240
private const val FAILURE_BODY_ELLIPSIS_CHARS = 3
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val DEFAULT_CONTENT_TYPE = "image/jpeg"
private const val STORAGE_VERSION_HEADER = "x-ms-version"
private const val BLOB_UPLOADER_LOG_TAG = "BlobUploader"

data class BlobUploadResult(
    val statusCode: Int,
    val failureMessage: String? = null,
)

class BlobUploader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun upload(
        uri: Uri,
        contentLengthBytes: Long,
        uploadUrl: String,
        requiredHeaders: Map<String, String>,
    ): BlobUploadResult =
        withContext(Dispatchers.IO) {
            val uploadHeaders = blobUploadHeaders(requiredHeaders)
            val contentType = blobContentType(uploadHeaders)
            val body = ContentUriRequestBody(context, uri, contentType, contentLengthBytes)
            Log.d(BLOB_UPLOADER_LOG_TAG, blobUploadRequestSummary(uploadHeaders, contentLengthBytes))
            val builder =
                Request.Builder()
                    .url(uploadUrl)
                    .put(body)
            uploadHeaders.forEach { (name, value) -> builder.header(name, value) }
            client.newCall(builder.build()).execute().use { response ->
                val statusCode = response.code
                if (statusCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                    BlobUploadResult(statusCode = statusCode)
                } else {
                    BlobUploadResult(
                        statusCode = statusCode,
                        failureMessage =
                            buildBlobFailureMessage(
                                statusCode = statusCode,
                                headers = response.headers,
                                bodyText = response.body?.string(),
                            ),
                    )
                }
            }
        }
}

internal fun blobUploadHeaders(requiredHeaders: Map<String, String>): Map<String, String> =
    LinkedHashMap(requiredHeaders).apply {
        if (keys.none { it.equals(STORAGE_VERSION_HEADER, ignoreCase = true) }) {
            put(STORAGE_VERSION_HEADER, DEFAULT_STORAGE_API_VERSION)
        }
    }

internal fun blobContentType(headers: Map<String, String>): String =
    headers.entries
        .firstOrNull { (name, _) -> name.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
        ?.value
        ?: DEFAULT_CONTENT_TYPE

internal fun blobUploadRequestSummary(
    headers: Map<String, String>,
    contentLengthBytes: Long,
): String {
    val headerSummary = headers.entries.joinToString(", ") { (name, value) -> "$name=$value" }
    return "Blob PUT contentLengthBytes=$contentLengthBytes headers={$headerSummary}"
}

internal fun buildBlobFailureMessage(
    statusCode: Int,
    headers: Headers,
    bodyText: String?,
): String {
    val parts = mutableListOf("Blob upload failed: $statusCode")
    headers["x-ms-error-code"]
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "azure=$it" }
    headers["x-ms-request-id"]
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "requestId=$it" }
    normalizeFailureBody(bodyText)
        ?.let { parts += "body=$it" }
    return parts.joinToString(" | ")
}

private fun normalizeFailureBody(bodyText: String?): String? {
    val normalized =
        bodyText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    return if (normalized.length <= MAX_FAILURE_BODY_CHARS) {
        normalized
    } else {
        normalized.take(MAX_FAILURE_BODY_CHARS - FAILURE_BODY_ELLIPSIS_CHARS) + "..."
    }
}

private class ContentUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val contentType: String,
    private val contentLengthBytes: Long,
) : RequestBody() {
    override fun contentType() = contentType.toMediaType()

    override fun contentLength() = contentLengthBytes

    override fun writeTo(sink: BufferedSink) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image stream" }
            sink.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
