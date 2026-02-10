package com.banknotify.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkMonitor : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isNetworkAvailable(context)) return

        Log.d(TAG, "Network restored, processing pending messages")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                processPendingMessages(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending messages", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        fun isNetworkAvailable(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        suspend fun processPendingMessages(context: Context) {
            val logDb = LogDatabase(context)
            val settings = SettingsManager(context)
            val sender = TelegramSender()
            val botToken = settings.botToken

            if (botToken.isBlank()) return

            val pendingLogs = logDb.getPendingLogs()
            Log.d(TAG, "Processing ${pendingLogs.size} pending messages")

            for (log in pendingLogs) {
                val chatId = when (log.transactionType) {
                    "입금" -> settings.depositChatId
                    "출금" -> settings.withdrawalChatId
                    else -> continue
                }
                if (chatId.isBlank()) continue

                val message = rebuildMessage(log, settings)
                val (success, attempts) = sender.sendWithRetry(botToken, chatId, message)

                if (success) {
                    logDb.updateTelegramStatus(log.id, "성공", attempts)
                } else {
                    logDb.updateTelegramStatus(log.id, "실패", log.retryCount + attempts)
                    NotificationHelper.showSendFailure(
                        context, log.transactionType,
                        log.amount, log.senderName
                    )
                }
            }
        }

        private fun rebuildMessage(log: LogEntry, settings: SettingsManager): String {
            val deviceLabel = if (log.deviceName.isNotBlank()) {
                "${log.deviceNumber}번 - ${log.deviceName}"
            } else {
                "${log.deviceNumber}번"
            }

            val type = when (log.transactionType) {
                "입금" -> TransactionType.DEPOSIT
                "출금" -> TransactionType.WITHDRAWAL
                else -> TransactionType.UNKNOWN
            }
            val status = when (log.transactionStatus) {
                "실패" -> TransactionStatus.FAILED
                "취소" -> TransactionStatus.CANCELLED
                else -> TransactionStatus.NORMAL
            }

            val notification = BankNotification(
                bankName = log.bankName,
                amount = log.amount.ifBlank { null },
                senderName = log.senderName.ifBlank { null },
                accountInfo = log.accountInfo.ifBlank { null },
                originalText = log.originalText,
                packageName = log.packageName,
                transactionType = type,
                transactionStatus = status,
                paymentMethod = log.paymentMethod,
                timestamp = log.timestamp
            )
            return TelegramSender().formatBankNotification(notification, deviceLabel)
        }
    }
}
