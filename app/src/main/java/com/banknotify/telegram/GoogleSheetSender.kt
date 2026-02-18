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

            val body = json.toString()
            postWithRedirect(webhookUrl, body)
        } catch (e: Exception) {
            Log.e(TAG, "Google Sheet send failed: ${e.message}")
        }
    }

    private fun postWithRedirect(targetUrl: String, body: String, maxRedirects: Int = 5) {
        var url = targetUrl
        var redirectCount = 0

        while (redirectCount < maxRedirects) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode

            if (responseCode in 301..303 || responseCode == 307 || responseCode == 308) {
                val redirectUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (redirectUrl.isNullOrBlank()) {
                    Log.e(TAG, "Redirect with no Location header")
                    return
                }
                url = redirectUrl
                redirectCount++
                Log.d(TAG, "Redirect $redirectCount → $url")
            } else {
                if (responseCode in 200..299) {
                    Log.d(TAG, "Google Sheet sent OK ($responseCode)")
                } else {
                    Log.w(TAG, "Google Sheet error: $responseCode")
                }
                conn.disconnect()
                return
            }
        }
        Log.e(TAG, "Too many redirects")
    }
}
