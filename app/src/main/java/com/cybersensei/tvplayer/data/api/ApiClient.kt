package com.cybersensei.tvplayer.data.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private var api: CyberSenseiApi? = null
    private var currentBaseUrl: String? = null
    private var currentApiKey: String? = null

    fun getApi(baseUrl: String, apiKey: String): CyberSenseiApi {
        if (api != null && currentBaseUrl == baseUrl && currentApiKey == apiKey) {
            return api!!
        }

        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-TV-API-Key", apiKey)
                .addHeader("User-Agent", "CyberSenseiTV/Android")
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(CyberSenseiApi::class.java)
        currentBaseUrl = baseUrl
        currentApiKey = apiKey
        return api!!
    }

    fun reset() {
        api = null
        currentBaseUrl = null
        currentApiKey = null
    }
}
