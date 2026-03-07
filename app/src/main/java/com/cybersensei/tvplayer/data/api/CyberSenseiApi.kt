package com.cybersensei.tvplayer.data.api

import com.cybersensei.tvplayer.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface CyberSenseiApi {

    @POST("api/tv/heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest): Response<HeartbeatResponse>

    @GET("api/tv/playlist")
    suspend fun getPlaylist(): Response<PlaylistResponse>

    @GET("api/tv/check-update")
    suspend fun checkUpdate(): Response<UpdateCheckResponse>

    @GET("api/tv/time")
    suspend fun getTime(): Response<TimeResponse>

    @Streaming
    @GET("api/tv/media/{id}/download")
    suspend fun downloadMedia(
        @Path("id") mediaId: Int,
        @Header("Range") range: String? = null
    ): Response<ResponseBody>

    @Streaming
    @GET("api/tv/download-app")
    suspend fun downloadApp(
        @Header("Range") range: String? = null
    ): Response<ResponseBody>
}
