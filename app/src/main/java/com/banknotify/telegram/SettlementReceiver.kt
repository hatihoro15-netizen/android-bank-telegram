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

        val now = System.currentTimeMillis()
        val sinceTimestamp = settings.lastSettlementTimestamp.let {
            if (it > 0) it else settings.lastResetTimestamp
        }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        val rangeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
        val timeRange = "${rangeFormat.format(Date(sinceTimestamp))}~${rangeFormat.format(Date(now))}"

        // 입금 정산
        if (settings.depositEnabled && settings.depositChatId.isNotBlank()) {
            val allLogs = logDb.getLogsSinceByTypeAndStatus(sinceTimestamp, "입금")
            val message = if (allLogs.isNotEmpty()) {
                buildSettlementMessage(TransactionType.DEPOSIT, allLogs, timeFormat, timeRange)
            } else {
                buildEmptySettlementMessage(TransactionType.DEPOSIT, timeRange)
            }
            sender.sendWithRetry(botToken, settings.depositChatId, message)
            Log.d(TAG, "Deposit settlement sent (${allLogs.size} logs, $timeRange)")
        }

        // 출금 정산
        if (settings.withdrawalEnabled && settings.withdrawalChatId.isNotBlank()) {
            val allLogs = logDb.getLogsSinceByTypeAndStatus(sinceTimestamp, "출금")
            val message = if (allLogs.isNotEmpty()) {
                buildSettlementMessage(TransactionType.WITHDRAWAL, allLogs, timeFormat, timeRange)
            } else {
                buildEmptySettlementMessage(TransactionType.WITHDRAWAL, timeRange)
            }
            sender.sendWithRetry(botToken, settings.withdrawalChatId, message)
            Log.d(TAG, "Withdrawal settlement sent (${allLogs.size} logs, $timeRange)")
        }

        // 정산 시간 업데이트
        settings.lastSettlementTimestamp = now

        // 오래된 로그 정리
        logDb.deleteOldLogs(settings.logRetentionDays)
    }

    private fun buildEmptySettlementMessage(transactionType: TransactionType, timeRange: String): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val typeLabel = if (transactionType == TransactionType.DEPOSIT) "입금" else "출금"
        val sb = StringBuilder()
        sb.appendLine("\uD83D\uDCCA <b>[${typeLabel} 정산] $timeRange $dateStr</b>")
        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
        sb.appendLine("해당 구간 거래 없음")
        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
        return sb.toString()
    }

    private fun buildSettlementMessage(
        transactionType: TransactionType,
        logs: List<LogEntry>,
        timeFormat: SimpleDateFormat,
        timeRange: String
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
            internalAmount = internalAmount,
            timeRange = timeRange
        )
    }

    private fun performReset(context: Context) {
        val settings = SettingsManager(context)
        val now = System.currentTimeMillis()
        settings.lastResetTimestamp = now
        settings.lastSettlementTimestamp = 0L
        Log.d(TAG, "Daily data reset at $now")
    }

    companion object {
        private const val TAG = "SettlementReceiver"
    }
}
