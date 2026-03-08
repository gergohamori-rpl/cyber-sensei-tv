package com.cybersensei.tvplayer.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import com.cybersensei.tvplayer.BuildConfig
import com.cybersensei.tvplayer.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class OtaUpdateManager(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String
) {
    private val api = ApiClient.getApi(baseUrl, apiKey)

    companion object {
        const val ACTION_INSTALL_COMPLETE = "com.cybersensei.tvplayer.INSTALL_COMPLETE"
    }

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

            installWithPackageInstaller(apkFile)
        } catch (e: Exception) {
            LogCollector.error("ota", "APK download/install error: ${e.message}", errorCode = "OTA_INSTALL_ERROR")
        }
    }

    private fun installWithPackageInstaller(apkFile: File) {
        try {
            val packageInstaller = context.packageManager.packageInstaller

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            try {
                session.openWrite("update.apk", 0, apkFile.length()).use { outputStream ->
                    FileInputStream(apkFile).use { inputStream ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        session.fsync(outputStream)
                    }
                }

                val intent = Intent(ACTION_INSTALL_COMPLETE).apply {
                    setPackage(context.packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                LogCollector.info("ota", "Committing install session $sessionId")
                session.commit(pendingIntent.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        } catch (e: Exception) {
            LogCollector.error("ota", "PackageInstaller failed: ${e.message}, falling back to intent install", errorCode = "PKG_INSTALLER_ERROR")
            installWithIntent(apkFile)
        }
    }

    private fun installWithIntent(apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                android.net.Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
            LogCollector.info("ota", "Fallback install intent launched")
        } catch (e: Exception) {
            LogCollector.error("ota", "Failed to launch fallback install: ${e.message}", errorCode = "INSTALL_LAUNCH_ERROR")
        }
    }

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent != null) {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                        LogCollector.info("ota", "User confirmation requested for install")
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    LogCollector.info("ota", "Update installed successfully!")
                }
                else -> {
                    LogCollector.error("ota", "Install failed: status=$status, message=$message", errorCode = "INSTALL_STATUS_$status")
                }
            }
        }
    }
}
