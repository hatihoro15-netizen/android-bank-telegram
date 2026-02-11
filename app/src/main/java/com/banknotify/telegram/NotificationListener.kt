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

    private lateinit var settings: SettingsManager
    private lateinit var logDb: LogDatabase

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        logDb = LogDatabase(this)

        // 오래된 로그 정리
        logDb.deleteOldLogs(settings.logRetentionDays)

        Log.d(TAG, "NotificationListener created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        // 모든 알림 패키지명 기록 (디버그용)
        val extras = sbn.notification.extras
        val rawTitle = extras.getCharSequence("android.title")?.toString() ?: ""
        val rawText = extras.getCharSequence("android.text")?.toString() ?: ""
        saveRecentPackage(packageName, rawTitle, rawText)

        // 푸시 알림 감지 꺼져 있으면 무시
        if (!settings.pushEnabled) return

        if (!parser.isMonitoredApp(packageName)) {
            Log.d(TAG, "SKIP unmonitored: $packageName | $rawTitle | $rawText")
            return
        }

        val title = rawTitle.ifBlank { null }
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()

        Log.d(TAG, "=== NOTIFICATION FROM: $packageName ===")
        Log.d(TAG, "  title='$title' text='$text' bigText='$bigText'")

        val notificationText = bigText ?: text
        if (notificationText.isNullOrBlank()) return

        val deviceNumber = settings.deviceNumber
        val deviceName = settings.deviceName
        val deviceLabel = settings.getDeviceLabel()

        // 무시되는 알림 체크 (광고/이벤트 등)
        val ignoreReason = parser.getIgnoreReason(title, notificationText, packageName)
        Log.d(TAG, "  ignoreReason=$ignoreReason")
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
            Log.d(TAG, "Ignored notification: $ignoreReason")
            return
        }

        val transactionType = parser.detectTransactionType(title, notificationText, packageName)
        Log.d(TAG, "  transactionType=${transactionType.label}")

        // UNKNOWN인데 transaction notification 통과 → 일단 무시하지 않고 진행
        if (transactionType == TransactionType.UNKNOWN) {
            Log.d(TAG, "  BLOCKED: transactionType is UNKNOWN")
            return
        }

        // 입금/출금 토글 확인
        when (transactionType) {
            TransactionType.DEPOSIT -> if (!settings.depositEnabled) return
            TransactionType.WITHDRAWAL -> if (!settings.withdrawalEnabled) return
            else -> return
        }

        val enabledDeposit = settings.getEnabledDepositMethodNames()
        val enabledWithdrawal = settings.getEnabledWithdrawalMethodNames()

        val notification = parser.parse(
            packageName, title, notificationText,
            enabledDeposit, enabledWithdrawal
        ).copy(source = "알림")

        // 중복 체크 (SMS + 푸시 동시 수신 방지)
        if (DuplicateDetector.isDuplicate(notification.amount, notification.senderName, "알림")) {
            Log.d(TAG, "Duplicate push notification, skipping (already sent via SMS)")
            return
        }

        // 내부거래 필터링
        val myAccounts = settings.myAccounts
        val excludeToggle = settings.excludeInternalTransfers
        Log.d(TAG, "Internal filter: toggle=$excludeToggle, accounts=${myAccounts.size}")
        myAccounts.forEach { acc ->
            Log.d(TAG, "  Stored account: bank='${acc.bankName}', name='${acc.accountName}', number='${acc.accountNumber}'")
        }
        Log.d(TAG, "  Notification: bank='${notification.bankName}', sender='${notification.senderName}', account='${notification.accountInfo}'")

        if (excludeToggle && SettingsManager.isInternalTransfer(notification, myAccounts)) {
            val internalNotification = notification.copy(transactionStatus = TransactionStatus.INTERNAL)
            logDb.insertLog(
                internalNotification, sentToTelegram = false,
                deviceNumber = deviceNumber, deviceName = deviceName,
                telegramStatus = ""
            )
            Log.d(TAG, "Internal transfer BLOCKED: ${notification.bankName} / ${notification.senderName}")
            return
        }
        Log.d(TAG, "Internal filter PASSED (not filtered)")

        // 결제수단 필터 확인 (정상 건만 필터, 실패/취소는 항상 전송)
        if (notification.transactionStatus == TransactionStatus.NORMAL) {
            val enabledMethods = when (transactionType) {
                TransactionType.DEPOSIT -> enabledDeposit
                TransactionType.WITHDRAWAL -> enabledWithdrawal
                else -> return
            }
            if (notification.paymentMethod !in enabledMethods && notification.paymentMethod != "알수없음") return
        }

        // 전송 대상 Chat ID 결정
        val chatId = when (transactionType) {
            TransactionType.DEPOSIT -> settings.depositChatId
            TransactionType.WITHDRAWAL -> settings.withdrawalChatId
            else -> return
        }

        Log.d(TAG, "[${notification.transactionType.label}${notification.transactionStatus.label}] " +
                "${notification.paymentMethod} ${notification.bankName} - ${notification.amount}")

        scope.launch {
            val botToken = settings.botToken

            if (botToken.isBlank() || chatId.isBlank()) {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "실패"
                )
                return@launch
            }

            // 네트워크 확인
            if (!NetworkMonitor.isNetworkAvailable(this@NotificationListener)) {
                // 오프라인 → 대기 큐에 저장
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "대기"
                )
                Log.d(TAG, "No network, queued for later")
                return@launch
            }

            val message = sender.formatBankNotification(notification, deviceLabel)
            val (success, attempts) = sender.sendWithRetry(botToken, chatId, message)

            if (success) {
                logDb.insertLog(
                    notification, sentToTelegram = true,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "성공"
                )
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
                Log.e(TAG, "Failed to send after $attempts attempts")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun saveRecentPackage(packageName: String, title: String, text: String) {
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

        fun getRecentPackages(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("debug_packages", MODE_PRIVATE)
            return prefs.getString("recent", "기록 없음") ?: "기록 없음"
        }
    }
}
