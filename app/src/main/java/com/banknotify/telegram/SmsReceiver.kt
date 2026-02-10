package com.banknotify.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val smsBankPatterns = mapOf(
        Regex("\\[?KB국민\\]?|\\[?국민은행\\]?|국민카드") to "KB국민은행",
        Regex("\\[?신한\\]?|신한은행|신한카드|\\[?신한SOL\\]?") to "신한은행",
        Regex("\\[?하나\\]?|하나은행|하나카드|KEB하나") to "하나은행",
        Regex("\\[?우리\\]?|우리은행|우리카드") to "우리은행",
        Regex("\\[?NH농협\\]?|\\[?농협\\]?|NH은행") to "NH농협은행",
        Regex("\\[?카카오뱅크\\]?") to "카카오뱅크",
        Regex("\\[?IBK기업\\]?|기업은행") to "IBK기업은행",
        Regex("\\[?SC제일\\]?|SC은행") to "SC제일은행",
        Regex("\\[?씨티\\]?|시티은행") to "씨티은행",
        Regex("\\[?대구은행\\]?|\\[?DGB\\]?") to "대구은행",
        Regex("\\[?부산은행\\]?|\\[?BNK부산\\]?") to "부산은행",
        Regex("\\[?경남은행\\]?|\\[?BNK경남\\]?") to "경남은행",
        Regex("\\[?광주은행\\]?") to "광주은행",
        Regex("\\[?전북은행\\]?") to "전북은행",
        Regex("\\[?제주은행\\]?") to "제주은행",
        Regex("\\[?산업은행\\]?|\\[?KDB\\]?") to "KDB산업은행",
        Regex("\\[?수협\\]?|수협은행") to "수협은행",
        Regex("\\[?우체국\\]?") to "우체국",
        Regex("\\[?새마을금고\\]?|\\[?새마을\\]?|\\[?MG\\]?") to "새마을금고",
        Regex("\\[?신협\\]?") to "신협",
        Regex("\\[?카카오페이\\]?") to "카카오페이",
        Regex("\\[?토스\\]?|\\[?toss\\]?", RegexOption.IGNORE_CASE) to "토스",
        Regex("\\[?네이버페이\\]?") to "네이버페이",
        Regex("\\[?페이코\\]?|\\[?PAYCO\\]?", RegexOption.IGNORE_CASE) to "페이코"
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val settings = SettingsManager(context)
        if (!settings.smsEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val fullBody = messages.joinToString("") { it.messageBody ?: "" }
        if (fullBody.isBlank()) return

        val senderAddress = messages.firstOrNull()?.originatingAddress ?: ""

        Log.d(TAG, "SMS received from: $senderAddress")
        processSms(context, fullBody, senderAddress, settings)
    }

    private fun processSms(
        context: Context,
        body: String,
        senderAddress: String,
        settings: SettingsManager
    ) {
        val parser = BankNotificationParser()
        val logDb = LogDatabase(context)
        val deviceNumber = settings.deviceNumber
        val deviceName = settings.deviceName

        // 은행명 감지
        val bankName = detectBankFromSms(body) ?: return

        // 거래 키워드 확인
        if (!parser.isTransactionNotification(null, body)) return

        // 무시 키워드 체크
        val ignoreReason = parser.getIgnoreReason(null, body)
        if (ignoreReason != null) {
            logDb.insertIgnoredLog(
                packageName = "sms:$senderAddress",
                bankName = bankName,
                originalText = body.trim(),
                ignoreReason = ignoreReason,
                deviceNumber = deviceNumber,
                deviceName = deviceName,
                source = "SMS"
            )
            Log.d(TAG, "Ignored SMS: $ignoreReason")
            return
        }

        val transactionType = parser.detectTransactionType(null, body)
        if (transactionType == TransactionType.UNKNOWN) return

        when (transactionType) {
            TransactionType.DEPOSIT -> if (!settings.depositEnabled) return
            TransactionType.WITHDRAWAL -> if (!settings.withdrawalEnabled) return
            else -> return
        }

        val enabledDeposit = settings.getEnabledDepositMethodNames()
        val enabledWithdrawal = settings.getEnabledWithdrawalMethodNames()

        val notification = parser.parse(
            "sms:$senderAddress", null, body,
            enabledDeposit, enabledWithdrawal
        ).copy(bankName = bankName, source = "SMS")

        // 중복 체크 (SMS + 푸시 동시 수신 방지)
        if (DuplicateDetector.isDuplicate(notification.amount, notification.senderName, "SMS")) {
            Log.d(TAG, "Duplicate SMS transaction, skipping")
            return
        }

        // 내부거래 필터링
        if (settings.excludeInternalTransfers &&
            SettingsManager.isInternalTransfer(notification, settings.myAccounts)) {
            val internalNotification = notification.copy(transactionStatus = TransactionStatus.INTERNAL)
            logDb.insertLog(
                internalNotification, sentToTelegram = false,
                deviceNumber = deviceNumber, deviceName = deviceName,
                telegramStatus = "", source = "SMS"
            )
            Log.d(TAG, "Internal transfer detected via SMS, skipping Telegram: ${notification.senderName}")
            return
        }

        // 결제수단 필터
        if (notification.transactionStatus == TransactionStatus.NORMAL) {
            val enabledMethods = when (transactionType) {
                TransactionType.DEPOSIT -> enabledDeposit
                TransactionType.WITHDRAWAL -> enabledWithdrawal
                else -> return
            }
            if (notification.paymentMethod !in enabledMethods && notification.paymentMethod != "알수없음") return
        }

        val chatId = when (transactionType) {
            TransactionType.DEPOSIT -> settings.depositChatId
            TransactionType.WITHDRAWAL -> settings.withdrawalChatId
            else -> return
        }

        val deviceLabel = settings.getDeviceLabel()

        Log.d(TAG, "[SMS][${notification.transactionType.label}] ${notification.bankName} - ${notification.amount}")

        scope.launch {
            val sender = TelegramSender()
            val botToken = settings.botToken

            if (botToken.isBlank() || chatId.isBlank()) {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "실패", source = "SMS"
                )
                return@launch
            }

            if (!NetworkMonitor.isNetworkAvailable(context)) {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "대기", source = "SMS"
                )
                return@launch
            }

            val message = sender.formatBankNotification(notification, deviceLabel)
            val (success, attempts) = sender.sendWithRetry(botToken, chatId, message)

            if (success) {
                logDb.insertLog(
                    notification, sentToTelegram = true,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "성공", source = "SMS"
                )
            } else {
                logDb.insertLog(
                    notification, sentToTelegram = false,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = "실패", source = "SMS"
                )
                NotificationHelper.showSendFailure(
                    context,
                    notification.transactionType.label,
                    notification.amount ?: "",
                    notification.senderName ?: ""
                )
                Log.e(TAG, "Failed SMS after $attempts attempts")
            }
        }
    }

    private fun detectBankFromSms(body: String): String? {
        for ((pattern, bankName) in smsBankPatterns) {
            if (pattern.containsMatchIn(body)) return bankName
        }
        return null
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
