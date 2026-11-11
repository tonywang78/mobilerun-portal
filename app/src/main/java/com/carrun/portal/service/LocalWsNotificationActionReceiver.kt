package com.carrun.portal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.carrun.portal.config.ConfigManager

class LocalWsNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CarrunAccessibilityService.ACTION_DISABLE_LOCAL_WS_SERVER) {
            return
        }

        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        configManager.setWebSocketEnabledWithNotification(false)
        CarrunAccessibilityService.getInstance()?.hideLocalWebSocketConnectionNotification()
        Log.i(
            CarrunAccessibilityService.TAG,
            "Disabled local WebSocket server from notification action",
        )
    }
}
