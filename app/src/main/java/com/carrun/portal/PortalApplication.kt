package com.carrun.portal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.carrun.portal.auth.DeviceCodeAuthClient
import com.carrun.portal.config.ConfigManager
import com.carrun.portal.keepalive.KeepAliveController
import com.carrun.portal.service.http402BlockedPresentationState
import com.carrun.portal.sms.SmsBridgeReporter
import com.carrun.portal.sms.SmsStore
import com.carrun.portal.state.AppForegroundTransitionTracker
import com.carrun.portal.state.AppVisibilityTracker
import com.carrun.portal.state.ConnectionStateManager

class PortalApplication : Application() {
    private val foregroundTransitionTracker =
        AppForegroundTransitionTracker(
            onForeground = ::onAppForegrounded,
            onBackground = ::onAppBackgrounded,
        )

    override fun onCreate() {
        super.onCreate()
        val configManager = ConfigManager.getInstance(this)
        configManager.onCloudSessionDropped = { token -> DeviceCodeAuthClient().signOut(token) }
        configManager.onSmsReset = { SmsStore.getInstance(this).resetKeepingRecentAsync() }
        SmsBridgeReporter.install(this)
        val http402Snapshot = configManager.reverseJoinHttp402Snapshot()
        http402BlockedPresentationState(
            blocked = http402Snapshot.blocked,
            explicitlyDisconnected = http402Snapshot.explicitlyDisconnected,
        )?.let(ConnectionStateManager::setState)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) {
                    foregroundTransitionTracker.onActivityStarted()
                }

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    foregroundTransitionTracker.onActivityStopped()
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun onAppForegrounded() {
        AppVisibilityTracker.setForeground(true)
        KeepAliveController.retryStartupIfEnabledAndInactive(this)?.let { reason ->
            Log.w(
                TAG,
                "Deferred keep-awake startup still blocked after app entered foreground: $reason",
            )
        }
    }

    private fun onAppBackgrounded() {
        AppVisibilityTracker.setForeground(false)
    }

    companion object {
        private const val TAG = "PortalApplication"
    }
}
