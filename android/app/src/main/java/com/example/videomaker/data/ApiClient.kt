package com.example.videomaker.data

import com.example.videomaker.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    private data class CacheKey(val baseUrl: String, val apiToken: String)

    private var cachedKey: CacheKey? = null
    private var cachedService: ApiService? = null

    fun create(baseUrl: String, apiToken: String): ApiService {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val key = CacheKey(normalizedBaseUrl, apiToken.trim())
        synchronized(this) {
            val service = cachedService
            if (service != null && cachedKey == key) {
                return service
            }
        }
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                if (key.apiToken.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${key.apiToken}")
                }
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(logging)
            .build()

        val service = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
        synchronized(this) {
            cachedKey = key
            cachedService = service
        }
        return service
    }

    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        require(trimmed.isNotBlank()) { "请先在设置中填写后端 Base URL" }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun buildAbsoluteUrl(baseUrl: String, pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }
        return normalizeBaseUrl(baseUrl).trimEnd('/') + "/" + pathOrUrl.trimStart('/')
    }

    fun toUserMessage(error: Throwable): String {
        return when (error) {
            is HttpException -> when (error.code()) {
                400 -> "请求参数有误，请检查标题、文案、模板或素材。"
                401 -> "API Token 无效或缺失，请在设置中重新填写。"
                413 -> "文件过大，超过后端上传限制。"
                500 -> "后端生成失败，请稍后重试或查看任务错误。"
                else -> "服务器返回错误：HTTP ${error.code()}"
            }
            is IOException -> "网络连接失败或超时，请检查服务器地址和网络。"
            is IllegalArgumentException -> error.message ?: "配置无效。"
            else -> error.message ?: "未知错误"
        }
    }
}
