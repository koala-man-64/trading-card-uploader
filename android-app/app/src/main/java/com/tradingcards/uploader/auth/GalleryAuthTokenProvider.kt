package com.tradingcards.uploader.auth

import android.app.Activity

interface GalleryAuthTokenProvider {
    suspend fun acquireGalleryManageToken(activity: Activity): String

    suspend fun acquireGalleryManageTokenSilent(): String
}
