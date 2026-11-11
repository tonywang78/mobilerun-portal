package com.carrun.caragent.device

import android.util.Log

/**
 * Opens target apps through Portal (a11y backend).
 */
class AppLauncher(
    private val portal: PortalTools,
) {
    fun open(packageName: String): String {
        return try {
            val portalMsg = portal.openApp(packageName)
            "opened $packageName; portal=$portalMsg"
        } catch (t: Throwable) {
            Log.w(TAG, "open failed: ${t.message}")
            t.message ?: "open failed"
        }
    }

    companion object {
        private const val TAG = "AppLauncher"
    }
}
