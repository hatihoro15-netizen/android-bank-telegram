package com.banknotify.telegram

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GoogleSheetSender {

    private const val TAG = "GoogleSheetSender"

    suspend fun send(webhookUrl: String, notification: BankNotification, deviceLabel: String, deviceNumber: Int = 1) {
        if (webhookUrl.isBlank()) return

        try {
            val json = JSONObject().apply {
                put("timestamp", notification.timestamp)
                put("bankName", notification.bankName)
                put("amount", notification.amount ?: "")
                put("senderName", notification.senderName ?: "")
                put("accountInfo", notification.accountInfo ?: "")
                put("transactionType", notification.transactionType.label)
                put("transactionStatus", notification.transactionStatus.label)
                put("paymentMethod", notification.paymentMethod)
                put("deviceLabel", deviceLabel)
                put("deviceNumber", deviceNumber)
                put("source", notification.source)
            }

            val url = URL(webhookUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..399) {
                Log.d(TAG, "Google Sheet sent OK")
            } else {
                Log.w(TAG, "Google Sheet error: $responseCode")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Google Sheet send failed: ${e.message}")
        }
    }
}
