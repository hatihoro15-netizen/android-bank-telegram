package com.banknotify.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting services")
            KeepAliveService.start(context)
            SettlementScheduler.scheduleDaily(context)
            SettlementScheduler.scheduleWatchdog(context)
        }
    }
}
