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
            val contentType = requiredHeaders["Content-Type"] ?: "image/jpeg"
            val body = ContentUriRequestBody(context, uri, contentType, contentLengthBytes)
            val builder =
                Request.Builder()
                    .url(uploadUrl)
                    .put(body)
            requiredHeaders.forEach { (name, value) -> builder.header(name, value) }
            client.newCall(builder.build()).execute().use { response -> response.code }
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
