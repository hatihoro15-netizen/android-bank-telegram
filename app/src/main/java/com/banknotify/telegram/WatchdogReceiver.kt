package com.banknotify.telegram

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Watchdog check triggered")

        val isEnabled = isNotificationListenerEnabled(context)

        if (!isEnabled) {
            Log.w(TAG, "NotificationListener is NOT enabled!")
            NotificationHelper.showServiceStopped(context)
        } else {
            NotificationHelper.clearServiceStoppedNotification(context)
            // 서비스가 활성화되어 있지만 죽었을 수 있으므로 rebind 요청
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NotificationListener::class.java)
                )
                Log.d(TAG, "Requested rebind for NotificationListener")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request rebind", e)
            }
        }

        // 다음 체크 스케줄
        SettlementScheduler.scheduleWatchdog(context)
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val cn = ComponentName(context, NotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
    }
}
