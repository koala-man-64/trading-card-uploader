package com.tradingcards.uploader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

internal fun resolveCurrentContentLengthBytes(
    context: Context,
    uri: Uri,
): Long? =
    when (uri.scheme) {
        ContentResolver.SCHEME_FILE ->
            uri.path
                ?.let(::File)
                ?.takeIf { it.exists() }
                ?.length()
        else ->
            resolveAssetFileLength(context, uri)
                ?: queryOpenableContentLength(context, uri)
    }

internal fun currentUploadContentLength(
    storedContentLengthBytes: Long,
    resolvedContentLengthBytes: Long?,
): Long = resolvedContentLengthBytes ?: storedContentLengthBytes

private fun resolveAssetFileLength(
    context: Context,
    uri: Uri,
): Long? =
    context.contentResolver.openAssetFileDescriptor(uri, "r").use { descriptor ->
        descriptor
            ?.length
            ?.takeIf { it >= 0 }
    }

private fun queryOpenableContentLength(
    context: Context,
    uri: Uri,
): Long? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex)
            } else {
                null
            }
        }
