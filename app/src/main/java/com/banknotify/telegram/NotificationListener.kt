package com.banknotify.telegram

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val parser = BankNotificationParser()
    private val sender = TelegramSender()
    private val recentNotificationKeys = mutableMapOf<String, Long>()

    private lateinit var settings: SettingsManager
    private lateinit var logDb: LogDatabase

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        logDb = LogDatabase(this)

        // 오래된 로그 정리
        logDb.deleteOldLogs(settings.logRetentionDays)

        // KeepAlive 서비스 시작
        KeepAliveService.start(this)

        Log.d(TAG, "NotificationListener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        lastNotificationTime = System.currentTimeMillis()
        Log.d(TAG, "NotificationListener CONNECTED")
        NotificationHelper.clearServiceStoppedNotification(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener DISCONNECTED - requesting rebind")
        NotificationHelper.showServiceStopped(this)
        requestRebind(android.content.ComponentName(this, NotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        // heartbeat 갱신 (모든 알림)
        lastNotificationTime = System.currentTimeMillis()

        // 오래된 알림 무시 (서비스 재연결 시 알림바에 남은 알림 재전달 방지)
        val notifAge = System.currentTimeMillis() - sbn.postTime
        if (notifAge > 60_000) {
            DebugLogger.log(this, "SKIP 오래된 알림 (${notifAge / 1000}초전) pkg=$packageName")
            return
        }

        // 그룹 요약 알림 무시 (개별 알림만 처리)
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            DebugLogger.log(this, "SKIP 그룹요약 pkg=$packageName")
            return
        }

        // 알림 업데이트 중복 방지 (같은 내용 30초 내 무시)
        val notifContent = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""
        val notifKey = "${sbn.key}|${notifContent.hashCode()}"
        val now = System.currentTimeMillis()
        if (recentNotificationKeys.containsKey(notifKey) &&
            now - (recentNotificationKeys[notifKey] ?: 0) < 30_000) {
            DebugLogger.log(this, "SKIP key중복 pkg=$packageName")
            return
        }
        recentNotificationKeys[notifKey] = now
        recentNotificationKeys.entries.removeAll { now - it.value > 60_000 }

        // 모든 알림 패키지명 기록 (디버그용)
        val extras = sbn.notification.extras
        val rawTitle = extras.getCharSequence("android.title")?.toString() ?: ""
        val rawText = extras.getCharSequence("android.text")?.toString() ?: ""
        saveRecentPackage(packageName, rawTitle, rawText)

        // 푸시 알림 감지 꺼져 있으면 무시
        if (!settings.pushEnabled) return

        if (!parser.isMonitoredApp(packageName)) {
            // 금융/결제 관련 미등록 앱은 로그 기록 (디버깅용)
            if (packageName.contains("pay", ignoreCase = true) ||
                packageName.contains("bank", ignoreCase = true) ||
                packageName.contains("money", ignoreCase = true) ||
                packageName.contains("zero", ignoreCase = true) ||
                packageName.contains("kftc", ignoreCase = true) ||
                packageName.contains("toss", ignoreCase = true) ||
                packageName.contains("fin", ignoreCase = true) ||
                packageName.contains("kakao", ignoreCase = true)) {
                DebugLogger.log(this, "SKIP 미등록앱 pkg=$packageName | $rawTitle | ${rawText.take(40)}")
            }
            return
        }

        // 카카오톡: 거래 키워드 없으면 무시 (일반 카톡 필터)
        if (packageName == "com.kakao.talk") {
            if (!parser.isTransactionNotification(rawTitle, rawText)) {
                return
            }
        }

        val title = rawTitle.ifBlank { null }
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()

        val notificationText = bigText ?: text
        if (notificationText.isNullOrBlank()) {
            DebugLogger.log(this, "SKIP 빈텍스트 pkg=$packageName title=$rawTitle")
            return
        }

        DebugLogger.log(this, "수신 pkg=$packageName title=$title text=${notificationText.take(50)}")

        val deviceNumber = settings.deviceNumber
        val deviceName = settings.deviceName
        val deviceLabel = settings.getDeviceLabel()

        // 무시되는 알림 체크 (광고/이벤트 등)
        val ignoreReason = parser.getIgnoreReason(title, notificationText, packageName)
        if (ignoreReason != null) {
            val bankName = parser.getBankName(packageName)
            logDb.insertIgnoredLog(
                packageName = packageName,
                bankName = bankName,
                originalText = "${title.orEmpty()} ${notificationText}".trim(),
                ignoreReason = ignoreReason,
                deviceNumber = deviceNumber,
                deviceName = deviceName
            )
            DebugLogger.log(this, "SKIP 무시: $ignoreReason | $rawTitle $rawText")
            return
        }

        val transactionType = parser.detectTransactionType(title, notificationText, packageName)

        if (transactionType == TransactionType.UNKNOWN) {
            DebugLogger.log(this, "SKIP 거래유형불명 pkg=$packageName | $rawTitle | ${notificationText.take(60)}")
            return
        }

        // 입금/출금 토글 확인
        when (transactionType) {
            TransactionType.DEPOSIT -> if (!settings.depositEnabled) {
                DebugLogger.log(this, "SKIP 입금감지OFF")
                return
            }
            TransactionType.WITHDRAWAL -> if (!settings.withdrawalEnabled) {
                DebugLogger.log(this, "SKIP 출금감지OFF")
                return
            }
            else -> return
        }

        val enabledDeposit = settings.getEnabledDepositMethodNames()
        val enabledWithdrawal = settings.getEnabledWithdrawalMethodNames()

        val notification = parser.parse(
            packageName, title, notificationText,
            enabledDeposit, enabledWithdrawal
        ).copy(source = "알림")

        DebugLogger.log(this, "파싱결과 ${transactionType.label} ${notification.paymentMethod} ${notification.amount} ${notification.senderName} bank=${notification.bankName}")

        // 중복 체크 1: 메모리 기반 (다른 앱에서 같은 거래 알림 방지, 30초)
        if (DuplicateDetector.isDuplicate(notification.amount, notification.senderName, packageName)) {
            DebugLogger.log(this, "SKIP 메모리중복 ${notification.amount} ${notification.senderName}")
            return
        }

        // 중복 체크 2: DB 기반 (서비스 재연결 시에도 확실하게 잡음, 5분)
        if (logDb.isDuplicateInDb(
                notification.amount ?: "", notification.senderName ?: "",
                notification.bankName)) {
            DebugLogger.log(this, "SKIP DB중복 ${notification.amount} ${notification.senderName}")
            return
        }

        // 내부거래 필터링
        val myAccounts = settings.myAccounts
        val excludeToggle = settings.excludeInternalTransfers

        if (excludeToggle && SettingsManager.isInternalTransfer(notification, myAccounts)) {
            val internalNotification = notification.copy(transactionStatus = TransactionStatus.INTERNAL)
            logDb.insertLog(
                internalNotification, sentToTelegram = false,
                deviceNumber = deviceNumber, deviceName = deviceName,
                telegramStatus = ""
            )
            DebugLogger.log(this, "SKIP 내부거래 ${notification.bankName} ${notification.senderName}")
            return
        }

        // 결제수단 필터 확인 (정상 건만 필터, 실패/취소는 항상 전송)
        if (notification.transactionStatus == TransactionStatus.NORMAL) {
            val enabledMethods = when (transactionType) {
                TransactionType.DEPOSIT -> enabledDeposit
                TransactionType.WITHDRAWAL -> enabledWithdrawal
                else -> return
            }
            if (notification.paymentMethod !in enabledMethods && notification.paymentMethod != "알수없음") {
                DebugLogger.log(this, "SKIP 결제수단필터 ${notification.paymentMethod}")
                return
            }
        }

        // 전송 대상 Chat ID 결정
        val chatId = when (transactionType) {
            TransactionType.DEPOSIT -> settings.depositChatId
            TransactionType.WITHDRAWAL -> settings.withdrawalChatId
            else -> return
        }

        DebugLogger.log(this, "전송시도 ${notification.transactionType.label} ${notification.amount} ${notification.senderName}")

        scope.launch {
            val botToken = settings.botToken

            if (botToken.isBlank() || chatId.isBlank()) {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "실패"
                )
                DebugLogger.log(this@NotificationListener, "실패: 토큰/채팅ID 비어있음")
                return@launch
            }

            if (!NetworkMonitor.isNetworkAvailable(this@NotificationListener)) {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "대기"
                )
                DebugLogger.log(this@NotificationListener, "대기: 네트워크 없음")
                return@launch
            }

            val message = sender.formatBankNotification(notification, deviceLabel)
            val (success, attempts) = sender.sendWithRetry(botToken, chatId, message)

            // 구글 시트 전송 (실패해도 무시)
            val sheetUrl = settings.googleSheetUrl
            if (sheetUrl.isNotBlank()) {
                GoogleSheetSender.send(sheetUrl, notification, deviceLabel)
            }

            if (success) {
                logDb.insertLog(
                    notification, sentToTelegram = true,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "성공"
                )
                DebugLogger.log(this@NotificationListener, "성공 ${notification.transactionType.label} ${notification.amount} ${notification.senderName}")
            } else {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "실패"
                )
                // 3회 재시도 실패 → 경고 알림
                NotificationHelper.showSendFailure(
                    this@NotificationListener,
                    notification.transactionType.label,
                    notification.amount ?: "",
                    notification.senderName ?: ""
                )
                DebugLogger.log(this@NotificationListener, "실패 ${notification.amount} ${notification.senderName} (${attempts}회 시도)")
                Log.e(TAG, "Failed to send after $attempts attempts")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun saveRecentPackage(packageName: String, title: String, text: String) {
        // 시스템 패키지 제외 (충전 알림 등)
        if (packageName.startsWith("com.android.") || packageName.startsWith("android.") ||
            packageName == "com.samsung.android.lool" || packageName == "com.sec.android.app.launcher") return
        try {
            val prefs = getSharedPreferences("debug_packages", MODE_PRIVATE)
            val existing = prefs.getString("recent", "") ?: ""
            val lines = existing.split("\n").filter { it.isNotBlank() }.toMutableList()
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val entry = "[$timestamp] $packageName | $title | $text"
            lines.add(0, entry)
            // 최근 50개만 유지
            val trimmed = lines.take(50).joinToString("\n")
            prefs.edit().putString("recent", trimmed).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveRecentPackage error", e)
        }
    }

    companion object {
        private const val TAG = "BankNotifyListener"

        @Volatile
        var lastNotificationTime: Long = System.currentTimeMillis()

        fun getRecentPackages(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("debug_packages", MODE_PRIVATE)
            return prefs.getString("recent", "기록 없음") ?: "기록 없음"
        }
    }
}
