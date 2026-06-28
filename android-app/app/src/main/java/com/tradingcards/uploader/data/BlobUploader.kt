package com.tradingcards.uploader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

internal const val DEFAULT_STORAGE_API_VERSION = "2023-11-03"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val DEFAULT_CONTENT_TYPE = "image/jpeg"
private const val STORAGE_VERSION_HEADER = "x-ms-version"

class BlobUploader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun upload(
        uri: Uri,
        contentLengthBytes: Long,
        uploadUrl: String,
        requiredHeaders: Map<String, String>,
    ): Int =
        withContext(Dispatchers.IO) {
            val uploadHeaders = blobUploadHeaders(requiredHeaders)
            val contentType = blobContentType(uploadHeaders)
            val body = ContentUriRequestBody(context, uri, contentType, contentLengthBytes)
            val builder =
                Request.Builder()
                    .url(uploadUrl)
                    .put(body)
            uploadHeaders.forEach { (name, value) -> builder.header(name, value) }
            client.newCall(builder.build()).execute().use { response -> response.code }
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
