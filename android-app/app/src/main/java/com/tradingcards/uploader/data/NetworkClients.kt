package com.tradingcards.uploader.data

import android.content.Context
import coil.ImageLoader
import com.tradingcards.uploader.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val API_CONNECT_TIMEOUT_SECONDS = 15L
private const val API_IO_TIMEOUT_SECONDS = 45L

/**
 * App-scoped network singletons. Building a fresh [OkHttpClient] (and the
 * Retrofit/Coil stacks on top of it) per call throws away connection pooling
 * and, for Coil, its disk/memory cache — so these are built once and reused
 * for the lifetime of the process, mirroring [UploadRepository.database].
 */
object NetworkClients {
    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var sasIssuerClient: SasIssuerClient? = null

    @Volatile
    private var imageLoader: ImageLoader? = null

    fun okHttpClient(): OkHttpClient =
        okHttpClient ?: synchronized(this) {
            okHttpClient ?: OkHttpClient.Builder()
                .connectTimeout(API_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(API_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(API_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(API_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .also { okHttpClient = it }
        }

    fun sasIssuerClient(): SasIssuerClient =
        sasIssuerClient ?: synchronized(this) {
            sasIssuerClient ?: SasIssuerClient.create(
                baseUrl = BuildConfig.API_BASE_URL,
                client = okHttpClient(),
            ).also { sasIssuerClient = it }
        }

    fun imageLoader(context: Context): ImageLoader =
        imageLoader ?: synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient { okHttpClient() }
                .build()
                .also { imageLoader = it }
        }
}
