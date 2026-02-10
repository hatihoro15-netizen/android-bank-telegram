package com.banknotify.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettlementReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (type) {
                    "settlement" -> performSettlement(context)
                    "reset" -> performReset(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Settlement error: ${e.message}", e)
            } finally {
                SettlementScheduler.scheduleDaily(context)
                pendingResult.finish()
            }
        }
    }

    private suspend fun performSettlement(context: Context) {
        val settings = SettingsManager(context)
        val logDb = LogDatabase(context)
        val sender = TelegramSender()
        val botToken = settings.botToken

        if (botToken.isBlank()) return

        val sinceTimestamp = settings.lastResetTimestamp
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

        // 입금 정산
        if (settings.depositEnabled && settings.depositChatId.isNotBlank()) {
            val allLogs = logDb.getLogsSinceByTypeAndStatus(sinceTimestamp, "입금")
            if (allLogs.isNotEmpty()) {
                val message = buildSettlementMessage(TransactionType.DEPOSIT, allLogs, timeFormat)
                sender.sendWithRetry(botToken, settings.depositChatId, message)
                Log.d(TAG, "Deposit settlement sent")
            }
        }

        // 출금 정산
        if (settings.withdrawalEnabled && settings.withdrawalChatId.isNotBlank()) {
            val allLogs = logDb.getLogsSinceByTypeAndStatus(sinceTimestamp, "출금")
            if (allLogs.isNotEmpty()) {
                val message = buildSettlementMessage(TransactionType.WITHDRAWAL, allLogs, timeFormat)
                sender.sendWithRetry(botToken, settings.withdrawalChatId, message)
                Log.d(TAG, "Withdrawal settlement sent")
            }
        }

        // 오래된 로그 정리
        logDb.deleteOldLogs(settings.logRetentionDays)
    }

    private fun buildSettlementMessage(
        transactionType: TransactionType,
        logs: List<LogEntry>,
        timeFormat: SimpleDateFormat
    ): String {
        val sender = TelegramSender()

        // 상태별 분류
        val normalLogs = logs.filter { it.transactionStatus == "정상" }
        val failedLogs = logs.filter { it.transactionStatus == "실패" }
        val cancelledLogs = logs.filter { it.transactionStatus == "취소" }
        val internalLogs = logs.filter { it.transactionStatus == "내부거래" }

        val normalCount = normalLogs.size
        val normalAmount = normalLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }

        val failedCount = failedLogs.size
        val failedAmount = failedLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }

        val cancelledCount = cancelledLogs.size
        val cancelledAmount = cancelledLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }

        val internalCount = internalLogs.size
        val internalAmount = internalLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }

        // 실패 건 상세
        val failedItems = failedLogs.map {
            FailedSettlementItem(
                time = timeFormat.format(Date(it.timestamp)),
                method = it.paymentMethod,
                sender = it.senderName
            )
        }

        // 디바이스별 구분 (정상 건만)
        val deviceGroups = normalLogs.groupBy {
            if (it.deviceName.isNotBlank()) "${it.deviceNumber}번 - ${it.deviceName}"
            else "${it.deviceNumber}번"
        }

        val deviceSummaries = deviceGroups.map { (label, deviceLogs) ->
            val methodBreakdown = deviceLogs.groupBy { it.paymentMethod }.map { (method, methodLogs) ->
                SettlementItem(
                    methodName = method,
                    count = methodLogs.size,
                    totalAmount = methodLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
                )
            }.sortedByDescending { it.totalAmount }

            DeviceSettlementSummary(
                deviceLabel = label,
                totalCount = deviceLogs.size,
                totalAmount = deviceLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) },
                methodBreakdown = methodBreakdown
            )
        }

        // 감지 vs 전송 성공
        val totalDetected = logs.size
        val totalSent = logs.count { it.telegramStatus == "성공" }

        return sender.formatSettlement(
            transactionType = transactionType,
            normalItems = emptyList(),
            normalCount = normalCount,
            normalAmount = normalAmount,
            failedItems = failedItems,
            failedCount = failedCount,
            failedAmount = failedAmount,
            cancelledCount = cancelledCount,
            cancelledAmount = cancelledAmount,
            deviceSummaries = deviceSummaries,
            totalDetected = totalDetected,
            totalSent = totalSent,
            internalCount = internalCount,
            internalAmount = internalAmount
        )
    }

    private fun performReset(context: Context) {
        val settings = SettingsManager(context)
        settings.lastResetTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Daily data reset at ${System.currentTimeMillis()}")
    }

    companion object {
        private const val TAG = "SettlementReceiver"
    }
}
