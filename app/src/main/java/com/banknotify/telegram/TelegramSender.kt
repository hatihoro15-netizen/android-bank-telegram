package com.banknotify.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelegramSender {

    suspend fun sendMessage(botToken: String, chatId: String, message: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                val postData = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}" +
                        "&text=${URLEncoder.encode(message, "UTF-8")}" +
                        "&parse_mode=HTML"

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(postData)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode == 200) {
                    Result.success(true)
                } else {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    } catch (e: Exception) {
                        "HTTP $responseCode"
                    }
                    Result.failure(Exception("Telegram API error: $responseCode - $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 최대 3회 재시도 (5초, 15초, 30초 간격)
     * @return Pair(성공여부, 시도횟수)
     */
    suspend fun sendWithRetry(botToken: String, chatId: String, message: String): Pair<Boolean, Int> {
        val delays = longArrayOf(0, 5_000, 15_000, 30_000)
        for (attempt in 0..3) {
            if (attempt > 0) delay(delays[attempt])
            val result = sendMessage(botToken, chatId, message)
            if (result.isSuccess) return true to (attempt + 1)
        }
        return false to 4
    }

    fun formatBankNotification(
        notification: BankNotification,
        deviceLabel: String,
        isTest: Boolean = false
    ): String {
        val sb = StringBuilder()
        val type = notification.transactionType
        val status = notification.transactionStatus

        val emoji = when (status) {
            TransactionStatus.FAILED -> "\u274C"
            TransactionStatus.CANCELLED -> "\uD83D\uDEAB"
            else -> type.emoji
        }

        val statusSuffix = when (status) {
            TransactionStatus.FAILED -> status.label
            TransactionStatus.CANCELLED -> status.label
            else -> ""
        }

        val testTag = if (isTest) " [\uD83E\uDDEA\uD14C\uC2A4\uD2B8]" else ""
        val sourceTag = if (notification.source == "SMS") " [SMS]" else " [\uC54C\uB9BC]"
        val typeLabel = "${type.label}${statusSuffix}"
        val amount = notification.amount ?: ""
        val sender = notification.senderName ?: ""

        sb.appendLine("<b>$emoji [$deviceLabel]$testTag [$typeLabel]$sourceTag ${notification.paymentMethod} $amount $sender</b>")
        sb.appendLine()
        sb.appendLine("\uD83C\uDFE6 은행: ${notification.bankName}")
        if (notification.amount != null) sb.appendLine("\uD83D\uDCB0 금액: ${notification.amount}")
        if (notification.senderName != null) {
            val label = if (type == TransactionType.DEPOSIT) "입금자" else "대상"
            sb.appendLine("\uD83D\uDC64 $label: ${notification.senderName}")
        }
        notification.accountInfo?.let { sb.appendLine("\uD83D\uDCCB 계좌: $it") }
        sb.appendLine()
        sb.appendLine("<b>원본 알림:</b>")
        sb.appendLine("<code>${escapeHtml(notification.originalText)}</code>")
        return sb.toString()
    }

    fun formatSettlement(
        transactionType: TransactionType,
        normalItems: List<SettlementItem>,
        normalCount: Int,
        normalAmount: Long,
        failedItems: List<FailedSettlementItem>,
        failedCount: Int,
        failedAmount: Long,
        cancelledCount: Int,
        cancelledAmount: Long,
        deviceSummaries: List<DeviceSettlementSummary>,
        totalDetected: Int,
        totalSent: Int,
        internalCount: Int = 0,
        internalAmount: Long = 0L,
        timeRange: String = ""
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val typeLabel = if (transactionType == TransactionType.DEPOSIT) "입금" else "출금"
        val rangeLabel = if (timeRange.isNotBlank()) "$timeRange " else ""

        val sb = StringBuilder()
        sb.appendLine("\uD83D\uDCCA <b>[${typeLabel} 정산] $rangeLabel$dateStr</b>")
        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        sb.appendLine("\u2705 정상: ${normalCount}건 | ${formatAmount(normalAmount)}원")

        if (internalCount > 0) {
            sb.appendLine("\uD83D\uDD04 내부거래 제외: ${internalCount}건 | ${formatAmount(internalAmount)}원")
        }

        if (failedCount > 0) {
            sb.append("\u274C 실패: ${failedCount}건 | ${formatAmount(failedAmount)}원")
            if (failedItems.isNotEmpty()) {
                val details = failedItems.joinToString(", ") { "(${it.time} ${it.method} ${it.sender})" }
                sb.append(" $details")
            }
            sb.appendLine()
        }
        if (cancelledCount > 0) {
            sb.appendLine("\uD83D\uDEAB 취소: ${cancelledCount}건 | ${formatAmount(cancelledAmount)}원")
        }

        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")

        deviceSummaries.forEach { device ->
            sb.appendLine("[${device.deviceLabel}] ${device.totalCount}건 / ${formatAmount(device.totalAmount)}원")
            device.methodBreakdown.forEach { method ->
                sb.appendLine("  \u2022 ${method.methodName}: ${method.count}건 / ${formatAmount(method.totalAmount)}원")
            }
        }

        sb.appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
        val checkmark = if (totalDetected == totalSent) "\u2705" else "\u26A0\uFE0F"
        sb.appendLine("감지 ${totalDetected}건 중 전송 성공 ${totalSent}건 $checkmark")

        return sb.toString()
    }

    suspend fun sendServiceAlert(botToken: String, chatId: String, deviceLabel: String, message: String) {
        if (botToken.isBlank() || chatId.isBlank()) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val text = "\uD83D\uDD14 <b>[시스템 알림]</b> $deviceLabel\n$message\n\u23F0 $timestamp"
        sendWithRetry(botToken, chatId, text)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    companion object {
        fun parseAmountToLong(amountStr: String): Long {
            return amountStr.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
        }

        fun formatAmount(amount: Long): String {
            return String.format("%,d", amount)
        }
    }
}

data class SettlementItem(
    val methodName: String,
    val count: Int,
    val totalAmount: Long
)

data class FailedSettlementItem(
    val time: String,
    val method: String,
    val sender: String
)

data class DeviceSettlementSummary(
    val deviceLabel: String,
    val totalCount: Int,
    val totalAmount: Long,
    val methodBreakdown: List<SettlementItem>
)
