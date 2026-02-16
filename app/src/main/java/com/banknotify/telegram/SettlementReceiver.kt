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
                    "settlement" -> performSettlement(context, isDaily = false)
                    "settlement_daily" -> performSettlement(context, isDaily = true)
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

    private suspend fun performSettlement(context: Context, isDaily: Boolean) {
        val settings = SettingsManager(context)

        // 정산 전송 꺼져있으면 스킵
        if (!settings.settlementEnabled) {
            Log.d(TAG, "Settlement disabled on this device")
            return
        }

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
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val deviceLabel = settings.getDeviceLabel()

        // 구간 로그 조회
        val depositLogs = if (settings.depositEnabled) {
            logDb.getLogsSinceByTypeAndStatus(sinceTimestamp, "입금")
        } else emptyList()

        val withdrawalLogs = if (settings.withdrawalEnabled) {
            logDb.getLogsSinceByTypeAndStatus(sinceTimestamp, "출금")
        } else emptyList()

        // 구간 정산 메시지
        val message = buildCombinedSettlementMessage(
            depositLogs, withdrawalLogs, timeFormat, timeRange, dateStr, deviceLabel
        )

        // 입금 채팅방에 전송
        val depositChatId = settings.depositChatId
        val withdrawalChatId = settings.withdrawalChatId

        if (depositChatId.isNotBlank()) {
            sender.sendWithRetry(botToken, depositChatId, message)
        }
        if (withdrawalChatId.isNotBlank() && withdrawalChatId != depositChatId) {
            sender.sendWithRetry(botToken, withdrawalChatId, message)
        }

        // 23:30 일일 종합 정산
        if (isDaily) {
            val dailySince = settings.lastResetTimestamp
            val dailyRange = "${rangeFormat.format(Date(dailySince))}~${rangeFormat.format(Date(now))}"

            val dailyDepositLogs = if (settings.depositEnabled) {
                logDb.getLogsSinceByTypeAndStatus(dailySince, "입금")
            } else emptyList()

            val dailyWithdrawalLogs = if (settings.withdrawalEnabled) {
                logDb.getLogsSinceByTypeAndStatus(dailySince, "출금")
            } else emptyList()

            val dailyMessage = buildDailySummaryMessage(
                dailyDepositLogs, dailyWithdrawalLogs, timeFormat, dailyRange, dateStr, deviceLabel
            )

            if (depositChatId.isNotBlank()) {
                sender.sendWithRetry(botToken, depositChatId, dailyMessage)
            }
            if (withdrawalChatId.isNotBlank() && withdrawalChatId != depositChatId) {
                sender.sendWithRetry(botToken, withdrawalChatId, dailyMessage)
            }
        }

        Log.d(TAG, "Settlement sent (daily=$isDaily, deposit=${depositLogs.size}, withdrawal=${withdrawalLogs.size}, $timeRange)")

        // 정산 시간 업데이트
        settings.lastSettlementTimestamp = now

        // 오래된 로그 정리
        logDb.deleteOldLogs(settings.logRetentionDays)
    }

    private fun buildDailySummaryMessage(
        depositLogs: List<LogEntry>,
        withdrawalLogs: List<LogEntry>,
        timeFormat: SimpleDateFormat,
        timeRange: String,
        dateStr: String,
        deviceLabel: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("\uD83D\uDCCB <b>[${deviceLabel}] [\uD83D\uDCC5 일일 종합] $dateStr</b>")
        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        // 입금 합계
        val depositNormal = depositLogs.filter { it.transactionStatus == "정상" }
        val depositAmount = depositNormal.sumOf { TelegramSender.parseAmountToLong(it.amount) }
        val depositFailed = depositLogs.filter { it.transactionStatus == "실패" }
        val depositCancelled = depositLogs.filter { it.transactionStatus == "취소" }

        sb.appendLine("\uD83D\uDCB0 <b>입금 합계</b>")
        sb.appendLine("  \u2705 정상: ${depositNormal.size}건 | ${TelegramSender.formatAmount(depositAmount)}원")
        if (depositFailed.isNotEmpty()) {
            val amt = depositFailed.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            sb.appendLine("  \u274C 실패: ${depositFailed.size}건 | ${TelegramSender.formatAmount(amt)}원")
        }
        if (depositCancelled.isNotEmpty()) {
            val amt = depositCancelled.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            sb.appendLine("  \uD83D\uDEAB 취소: ${depositCancelled.size}건 | ${TelegramSender.formatAmount(amt)}원")
        }
        sb.appendLine()

        // 출금 합계
        val withdrawalNormal = withdrawalLogs.filter { it.transactionStatus == "정상" }
        val withdrawalAmount = withdrawalNormal.sumOf { TelegramSender.parseAmountToLong(it.amount) }
        val withdrawalFailed = withdrawalLogs.filter { it.transactionStatus == "실패" }
        val withdrawalCancelled = withdrawalLogs.filter { it.transactionStatus == "취소" }

        sb.appendLine("\uD83D\uDCB8 <b>출금 합계</b>")
        sb.appendLine("  \u2705 정상: ${withdrawalNormal.size}건 | ${TelegramSender.formatAmount(withdrawalAmount)}원")
        if (withdrawalFailed.isNotEmpty()) {
            val amt = withdrawalFailed.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            sb.appendLine("  \u274C 실패: ${withdrawalFailed.size}건 | ${TelegramSender.formatAmount(amt)}원")
        }
        if (withdrawalCancelled.isNotEmpty()) {
            val amt = withdrawalCancelled.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            sb.appendLine("  \uD83D\uDEAB 취소: ${withdrawalCancelled.size}건 | ${TelegramSender.formatAmount(amt)}원")
        }

        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        // 순이익 (입금 - 출금)
        val netAmount = depositAmount - withdrawalAmount
        val netEmoji = if (netAmount >= 0) "\uD83D\uDCC8" else "\uD83D\uDCC9"
        sb.appendLine("$netEmoji <b>순이익: ${TelegramSender.formatAmount(netAmount)}원</b>")

        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        // 전체 감지/전송 현황
        val allLogs = depositLogs + withdrawalLogs
        val totalDetected = allLogs.size
        val totalSent = allLogs.count { it.telegramStatus == "성공" }
        val checkmark = if (totalDetected == totalSent) "\u2705" else "\u26A0\uFE0F"
        sb.appendLine("감지 ${totalDetected}건 중 전송 성공 ${totalSent}건 $checkmark")

        return sb.toString()
    }

    private fun buildCombinedSettlementMessage(
        depositLogs: List<LogEntry>,
        withdrawalLogs: List<LogEntry>,
        timeFormat: SimpleDateFormat,
        timeRange: String,
        dateStr: String,
        deviceLabel: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("\uD83D\uDCCA <b>[${deviceLabel}] [정산] $timeRange $dateStr</b>")
        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        // 입금 섹션
        sb.appendLine("\uD83D\uDCB0 <b>입금</b>")
        if (depositLogs.isEmpty()) {
            sb.appendLine("  거래 없음")
        } else {
            appendTypeSection(sb, depositLogs, timeFormat)
        }
        sb.appendLine()

        // 출금 섹션
        sb.appendLine("\uD83D\uDCB8 <b>출금</b>")
        if (withdrawalLogs.isEmpty()) {
            sb.appendLine("  거래 없음")
        } else {
            appendTypeSection(sb, withdrawalLogs, timeFormat)
        }

        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        // 디바이스별 상세 (입금+출금 합산)
        val allNormalLogs = (depositLogs + withdrawalLogs).filter { it.transactionStatus == "정상" }
        if (allNormalLogs.isNotEmpty()) {
            val deviceGroups = allNormalLogs.groupBy {
                if (it.deviceName.isNotBlank()) "${it.deviceNumber}번 - ${it.deviceName}"
                else "${it.deviceNumber}번"
            }
            deviceGroups.forEach { (label, deviceLogs) ->
                val total = deviceLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
                sb.appendLine("[${label}] ${deviceLogs.size}건 / ${TelegramSender.formatAmount(total)}원")

                val methodBreakdown = deviceLogs.groupBy { "${it.transactionType}|${it.paymentMethod}" }
                    .map { (key, logs) ->
                        val parts = key.split("|")
                        val typeEmoji = if (parts[0] == "입금") "\uD83D\uDCB0" else "\uD83D\uDCB8"
                        Triple(typeEmoji, parts.getOrElse(1) { "" }, logs)
                    }
                    .sortedByDescending { it.third.sumOf { l -> TelegramSender.parseAmountToLong(l.amount) } }

                methodBreakdown.forEach { (emoji, method, logs) ->
                    val amt = logs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
                    sb.appendLine("  \u2022 $emoji $method: ${logs.size}건 / ${TelegramSender.formatAmount(amt)}원")
                }
            }
            sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
        }

        // 전체 감지/전송 현황
        val allLogs = depositLogs + withdrawalLogs
        val totalDetected = allLogs.size
        val totalSent = allLogs.count { it.telegramStatus == "성공" }
        val checkmark = if (totalDetected == totalSent) "\u2705" else "\u26A0\uFE0F"
        sb.appendLine("감지 ${totalDetected}건 중 전송 성공 ${totalSent}건 $checkmark")

        return sb.toString()
    }

    private fun appendTypeSection(sb: StringBuilder, logs: List<LogEntry>, timeFormat: SimpleDateFormat) {
        val normalLogs = logs.filter { it.transactionStatus == "정상" }
        val failedLogs = logs.filter { it.transactionStatus == "실패" }
        val cancelledLogs = logs.filter { it.transactionStatus == "취소" }
        val internalLogs = logs.filter { it.transactionStatus == "내부거래" }

        val normalAmount = normalLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
        sb.appendLine("  \u2705 정상: ${normalLogs.size}건 | ${TelegramSender.formatAmount(normalAmount)}원")

        if (internalLogs.isNotEmpty()) {
            val amt = internalLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            sb.appendLine("  \uD83D\uDD04 내부거래 제외: ${internalLogs.size}건 | ${TelegramSender.formatAmount(amt)}원")
        }
        if (failedLogs.isNotEmpty()) {
            val amt = failedLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            val details = failedLogs.joinToString(", ") {
                "(${timeFormat.format(Date(it.timestamp))} ${it.paymentMethod} ${it.senderName})"
            }
            sb.appendLine("  \u274C 실패: ${failedLogs.size}건 | ${TelegramSender.formatAmount(amt)}원 $details")
        }
        if (cancelledLogs.isNotEmpty()) {
            val amt = cancelledLogs.sumOf { TelegramSender.parseAmountToLong(it.amount) }
            sb.appendLine("  \uD83D\uDEAB 취소: ${cancelledLogs.size}건 | ${TelegramSender.formatAmount(amt)}원")
        }
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
