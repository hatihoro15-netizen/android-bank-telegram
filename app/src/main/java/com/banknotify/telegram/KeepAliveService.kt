package com.banknotify.telegram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask

class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var healthCheckTimer: Timer? = null
    private var silenceRecoveryAttempted: Boolean = false
    private var silenceAlertSent: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundNotification()
        acquireWakeLock()
        startHealthCheck()
        Log.d(TAG, "KeepAliveService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 서비스가 죽어도 시스템이 자동으로 재시작
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        healthCheckTimer?.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.w(TAG, "KeepAliveService destroyed - attempting restart")
        // 서비스가 죽으면 재시작 시도
        val restartIntent = Intent(this, KeepAliveService::class.java)
        startForegroundService(restartIntent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed - restarting service")
        val restartIntent = Intent(this, KeepAliveService::class.java)
        startForegroundService(restartIntent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "알림 감지 서비스",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "은행 알림 감지가 실행 중입니다"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("은행 알림 감지 중")
            .setContentText("입출금 알림을 실시간 감지하고 있습니다")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BankNotify::KeepAlive"
        ).apply {
            acquire()
        }
    }

    private fun startHealthCheck() {
        healthCheckTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    checkNotificationListenerAlive()
                }
            }, 60_000L, 60_000L) // 1분마다 체크
        }
    }

    private fun checkNotificationListenerAlive() {
        val cn = ComponentName(this, NotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = flat?.contains(cn.flattenToString()) == true

        if (!isEnabled) {
            Log.w(TAG, "NotificationListener is NOT enabled!")
            NotificationHelper.showServiceStopped(this)
        } else {
            NotificationHelper.clearServiceStoppedNotification(this)
        }

        // 무알림 감지 → 자동 복구 (24시간)
        if (isEnabled) {
            val lastTime = NotificationListener.lastNotificationTime
            val silentMinutes = (System.currentTimeMillis() - lastTime) / 60_000

            // 30분 무알림 → 자동 rebind 시도
            if (lastTime > 0 && silentMinutes >= 30 && !silenceRecoveryAttempted) {
                Log.w(TAG, "No notifications for ${silentMinutes}min - auto rebind")
                silenceRecoveryAttempted = true
                android.service.notification.NotificationListenerService.requestRebind(cn)
            }

            // 60분 무알림 → 자동 복구 실패, 텔레그램 경고
            if (lastTime > 0 && silentMinutes >= 60 && !silenceAlertSent) {
                Log.e(TAG, "No notifications for ${silentMinutes}min - recovery failed, alerting")
                silenceAlertSent = true
                android.service.notification.NotificationListenerService.requestRebind(cn)
                CoroutineScope(Dispatchers.IO).launch {
                    val settings = SettingsManager(this@KeepAliveService)
                    TelegramSender().sendServiceAlert(
                        settings.botToken, settings.depositChatId, settings.getDeviceLabel(),
                        "\u26A0\uFE0F ${silentMinutes}분간 알림 없음 - 자동 복구 실패, 앱 확인 필요"
                    )
                }
            }

            // 알림 정상 복구 → 플래그 리셋
            if (silentMinutes < 30) {
                silenceRecoveryAttempted = false
                silenceAlertSent = false
            }
        }
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (service.service.className == KeepAliveService::class.java.name) {
                    return true
                }
            }
            return false
        }
    }
}
