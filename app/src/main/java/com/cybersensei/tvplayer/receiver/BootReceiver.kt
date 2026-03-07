package com.cybersensei.tvplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cybersensei.tvplayer.ui.MainActivity
import com.cybersensei.tvplayer.ui.SetupActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = context.getSharedPreferences("cyber_sensei_tv", Context.MODE_PRIVATE)
            val hasConfig = !prefs.getString("server_url", null).isNullOrBlank() &&
                           !prefs.getString("api_key", null).isNullOrBlank()

            val targetActivity = if (hasConfig) MainActivity::class.java else SetupActivity::class.java
            val launchIntent = Intent(context, targetActivity).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
