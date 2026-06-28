package com.tradingcards.uploader.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tradingcards.uploader.model.GalleryImageDeleteRequest
import com.tradingcards.uploader.model.GalleryImageDeleteResponse
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.model.GallerySourceActionRequest
import com.tradingcards.uploader.model.GallerySourceActionResponse
import com.tradingcards.uploader.model.SasRequest
import com.tradingcards.uploader.model.SasResponse
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface SasIssuerClient {
    @GET("healthz")
    suspend fun healthz(): Response<Map<String, String>>

    @POST("v1/uploads/sas")
    suspend fun issueUploadSas(
        @Header("Authorization") authorization: String,
        @Body request: SasRequest,
    ): Response<SasResponse>

    @GET("v1/admin/gallery/images")
    suspend fun listGalleryImages(
        @Header("Authorization") authorization: String,
        @Query("category") category: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): Response<GalleryImagesResponse>

    @POST("v1/admin/gallery/actions/delete-source-group")
    suspend fun deleteGallerySourceGroup(
        @Header("Authorization") authorization: String,
        @Body request: GallerySourceActionRequest,
    ): Response<GallerySourceActionResponse>

    @POST("v1/admin/gallery/actions/delete-image")
    suspend fun deleteGalleryImage(
        @Header("Authorization") authorization: String,
        @Body request: GalleryImageDeleteRequest,
    ): Response<GalleryImageDeleteResponse>

    @POST("v1/admin/gallery/actions/reprocess-source")
    suspend fun reprocessGallerySource(
        @Header("Authorization") authorization: String,
        @Body request: GallerySourceActionRequest,
    ): Response<GallerySourceActionResponse>

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
