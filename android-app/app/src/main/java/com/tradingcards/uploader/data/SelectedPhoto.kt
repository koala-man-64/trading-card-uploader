package com.tradingcards.uploader.data

internal const val JPEG_CONTENT_TYPE = "image/jpeg"
internal const val HEIC_CONTENT_TYPE = "image/heic"
private const val HEIF_CONTENT_TYPE = "image/heif"

internal fun supportedSelectedPhotoContentType(rawContentType: String?): String? =
    when (rawContentType?.trim()?.lowercase()) {
        JPEG_CONTENT_TYPE, "image/jpg" -> JPEG_CONTENT_TYPE
        HEIC_CONTENT_TYPE, HEIF_CONTENT_TYPE -> HEIC_CONTENT_TYPE
        else -> null
    }

internal fun selectedPhotoFileExtension(contentType: String): String =
    when (contentType) {
        HEIC_CONTENT_TYPE -> ".heic"
        else -> ".jpg"
    }
