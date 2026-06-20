package com.example.videomaker.data

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("api/health")
    suspend fun health(): HealthResponse

    @GET("api/templates")
    suspend fun templates(): TemplatesResponse

    @GET("api/voices")
    suspend fun voices(): List<VoiceInfo>

    @Multipart
    @POST("api/upload")
    suspend fun upload(@Part file: MultipartBody.Part): UploadResponse

    @POST("api/jobs")
    suspend fun createJob(@Body request: CreateJobRequest): CreateJobResponse

    @POST("api/smart-jobs")
    suspend fun createSmartJob(@Body request: SmartJobRequest): CreateJobResponse

    @GET("api/history")
    suspend fun history(@Query("limit") limit: Int = 20): GenerationHistoryResponse

    @GET("api/jobs/{jobId}")
    suspend fun jobStatus(@Path("jobId") jobId: String): JobStatusResponse

    @GET("app/android/latest.json")
    suspend fun latestAndroidVersion(): AppUpdateResponse
}
