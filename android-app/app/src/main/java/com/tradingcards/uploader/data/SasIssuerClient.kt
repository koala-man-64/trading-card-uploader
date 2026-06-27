package com.tradingcards.uploader.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tradingcards.uploader.model.SasRequest
import com.tradingcards.uploader.model.SasResponse
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface SasIssuerClient {
    @GET("healthz")
    suspend fun healthz(): Response<Map<String, String>>

    @POST("v1/uploads/sas")
    suspend fun issueUploadSas(
        @Header("Authorization") authorization: String,
        @Body request: SasRequest,
    ): Response<SasResponse>

    companion object {
        fun create(baseUrl: String): SasIssuerClient {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SasIssuerClient::class.java)
        }
    }
}
