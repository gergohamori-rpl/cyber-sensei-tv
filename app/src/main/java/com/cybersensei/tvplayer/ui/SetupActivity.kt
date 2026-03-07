package com.cybersensei.tvplayer.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cybersensei.tvplayer.R
import com.cybersensei.tvplayer.data.api.ApiClient
import com.cybersensei.tvplayer.data.model.HeartbeatRequest
import kotlinx.coroutines.*

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cyber_sensei_tv", MODE_PRIVATE)

        val savedUrl = prefs.getString("server_url", null)
        val savedKey = prefs.getString("api_key", null)
        if (!savedUrl.isNullOrBlank() && !savedKey.isNullOrBlank()) {
            launchMain()
            return
        }

        setContentView(R.layout.activity_setup)

        val urlInput = findViewById<EditText>(R.id.inputServerUrl)
        val keyInput = findViewById<EditText>(R.id.inputApiKey)
        val connectBtn = findViewById<Button>(R.id.btnConnect)
        val statusText = findViewById<TextView>(R.id.textStatus)
        val progress = findViewById<ProgressBar>(R.id.progressBar)

        connectBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val key = keyInput.text.toString().trim()

            if (url.isBlank() || key.isBlank()) {
                statusText.text = "Please enter both server URL and API key"
                statusText.setTextColor(0xFFFF4444.toInt())
                return@setOnClickListener
            }

            connectBtn.isEnabled = false
            progress.visibility = View.VISIBLE
            statusText.text = "Connecting..."
            statusText.setTextColor(0xFFCCCCCC.toInt())

            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val api = ApiClient.getApi(url, key)
                        val request = HeartbeatRequest(
                            currentMediaId = null,
                            currentMediaTitle = null,
                            appVersion = "setup",
                            storageUsedBytes = 0,
                            storageFreeBytes = 0,
                            timezone = java.util.TimeZone.getDefault().id,
                            ipAddress = null,
                            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                            androidVersion = android.os.Build.VERSION.RELEASE,
                            logs = emptyList()
                        )
                        api.sendHeartbeat(request)
                    }

                    if (result.isSuccessful) {
                        prefs.edit()
                            .putString("server_url", url)
                            .putString("api_key", key)
                            .apply()
                        statusText.text = "Connected! Starting player..."
                        statusText.setTextColor(0xFF00FF88.toInt())
                        delay(1000)
                        launchMain()
                    } else {
                        statusText.text = "Connection failed: ${result.code()} ${result.message()}"
                        statusText.setTextColor(0xFFFF4444.toInt())
                        connectBtn.isEnabled = true
                        progress.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    statusText.text = "Error: ${e.message}"
                    statusText.setTextColor(0xFFFF4444.toInt())
                    connectBtn.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
