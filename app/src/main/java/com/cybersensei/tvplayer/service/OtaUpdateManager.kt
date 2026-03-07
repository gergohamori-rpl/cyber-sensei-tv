package com.cybersensei.tvplayer.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.cybersensei.tvplayer.BuildConfig
import com.cybersensei.tvplayer.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class OtaUpdateManager(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String
) {
    private val api = ApiClient.getApi(baseUrl, apiKey)

    suspend fun checkAndUpdate() {
        withContext(Dispatchers.IO) {
            try {
                val response = api.checkUpdate()
                if (!response.isSuccessful) {
                    LogCollector.warn("ota", "Update check failed: ${response.code()}")
                    return@withContext
                }

                val update = response.body() ?: return@withContext
                if (!update.hasUpdate) {
                    LogCollector.info("ota", "App is up to date")
                    return@withContext
                }

                val currentVersionCode: Int = BuildConfig.VERSION_CODE
                val serverVersionCode: Int = update.versionCode ?: 0

                if (serverVersionCode <= currentVersionCode) {
                    return@withContext
                }

                LogCollector.info("ota", "Update available: ${update.version} (code: $serverVersionCode)", details = mapOf(
                    "currentVersion" to BuildConfig.VERSION_NAME,
                    "currentCode" to currentVersionCode,
                    "newVersion" to update.version,
                    "newCode" to serverVersionCode,
                    "fileSize" to update.fileSize
                ))

                downloadAndInstall()
            } catch (e: Exception) {
                LogCollector.error("ota", "Update check error: ${e.message}", errorCode = "OTA_CHECK_ERROR")
            }
        }
    }

    private suspend fun downloadAndInstall() {
        try {
            val apkFile = File(context.cacheDir, "update.apk")
            val tempFile = File(context.cacheDir, "update.apk.tmp")

            LogCollector.info("ota", "Downloading update APK...")
            val startTime = System.currentTimeMillis()

            val response = api.downloadApp()
            if (!response.isSuccessful) {
                LogCollector.error("ota", "APK download failed: ${response.code()}", errorCode = "DOWNLOAD_FAILED")
                return
            }

            val body = response.body() ?: return

            withContext(Dispatchers.IO) {
                FileOutputStream(tempFile).use { fos ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            tempFile.renameTo(apkFile)
            val durationMs = (System.currentTimeMillis() - startTime).toInt()
            LogCollector.download(
                message = "Update APK downloaded",
                fileName = "update.apk",
                fileSize = apkFile.length(),
                durationMs = durationMs,
                success = true
            )

            installApk(apkFile)
        } catch (e: Exception) {
            LogCollector.error("ota", "APK download/install error: ${e.message}", errorCode = "OTA_INSTALL_ERROR")
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
            LogCollector.info("ota", "Install intent launched")
        } catch (e: Exception) {
            LogCollector.error("ota", "Failed to launch install: ${e.message}", errorCode = "INSTALL_LAUNCH_ERROR")
        }
    }
}
