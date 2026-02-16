package com.banknotify.telegram

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object SettlementScheduler {

    private const val REQUEST_SETTLEMENT = 2001
    private const val REQUEST_RESET = 2002
    private const val REQUEST_WATCHDOG = 2003
    private const val REQUEST_SETTLEMENT_0530 = 2004
    private const val REQUEST_SETTLEMENT_1130 = 2005
    private const val REQUEST_SETTLEMENT_1730 = 2006

    fun scheduleDaily(context: Context) {
        val settings = SettingsManager(context)

        // 23:30 정산 (일일 종합 포함)
        scheduleAlarm(context, settings.settlementHour, settings.settlementMinute,
            REQUEST_SETTLEMENT, "settlement_daily", SettlementReceiver::class.java)

        // 6시간 간격 추가 정산
        val times = listOf(5 to 30, 11 to 30, 17 to 30)
        val codes = listOf(REQUEST_SETTLEMENT_0530, REQUEST_SETTLEMENT_1130, REQUEST_SETTLEMENT_1730)
        times.forEachIndexed { i, (h, m) ->
            scheduleAlarm(context, h, m, codes[i], "settlement", SettlementReceiver::class.java)
        }

        // 23:55 리셋
        scheduleAlarm(context, 23, 55, REQUEST_RESET, "reset", SettlementReceiver::class.java)
    }

    fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WatchdogReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_WATCHDOG, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 5분 후
        val triggerAt = System.currentTimeMillis() + 5 * 60 * 1000
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAlarm(context, am, REQUEST_SETTLEMENT, SettlementReceiver::class.java)
        cancelAlarm(context, am, REQUEST_SETTLEMENT_0530, SettlementReceiver::class.java)
        cancelAlarm(context, am, REQUEST_SETTLEMENT_1130, SettlementReceiver::class.java)
        cancelAlarm(context, am, REQUEST_SETTLEMENT_1730, SettlementReceiver::class.java)
        cancelAlarm(context, am, REQUEST_RESET, SettlementReceiver::class.java)
        cancelWatchdog(context, am)
    }

    private fun scheduleAlarm(
        context: Context, hour: Int, minute: Int,
        requestCode: Int, actionType: String,
        receiverClass: Class<*>
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, receiverClass).apply {
            putExtra("type", actionType)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
    }

    private fun cancelAlarm(context: Context, am: AlarmManager, requestCode: Int, receiverClass: Class<*>) {
        val intent = Intent(context, receiverClass)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun cancelWatchdog(context: Context, am: AlarmManager) {
        val intent = Intent(context, WatchdogReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_WATCHDOG, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}
