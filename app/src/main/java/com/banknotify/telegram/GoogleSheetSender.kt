package com.banknotify.telegram

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GoogleSheetSender {

    private const val TAG = "GoogleSheetSender"

    suspend fun send(context: Context, webhookUrl: String, notification: BankNotification, deviceLabel: String, deviceNumber: Int = 1) {
        if (webhookUrl.isBlank()) {
            DebugLogger.log(context, "시트: URL 비어있음 → 스킵")
            return
        }

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
            DebugLogger.log(context, "시트: 전송시도 ${notification.amount} ${notification.senderName} → ${webhookUrl.take(60)}...")
            withContext(Dispatchers.IO) {
                postWithRedirect(context, webhookUrl, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Sheet send failed: ${e.message}")
            DebugLogger.log(context, "시트: 전송실패 ${e.message}")
        }
    }

    private fun postWithRedirect(context: Context, targetUrl: String, body: String, maxRedirects: Int = 5) {
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
                    DebugLogger.log(context, "시트: 리다이렉트인데 Location 없음")
                    return
                }
                url = redirectUrl
                redirectCount++
                DebugLogger.log(context, "시트: 리다이렉트 $redirectCount → ${url.take(60)}")
            } else {
                if (responseCode in 200..299) {
                    Log.d(TAG, "Google Sheet sent OK ($responseCode)")
                    DebugLogger.log(context, "시트: 전송성공 ($responseCode)")
                } else {
                    Log.w(TAG, "Google Sheet error: $responseCode")
                    DebugLogger.log(context, "시트: 에러 $responseCode")
                }
                conn.disconnect()
                return
            }
        }
        DebugLogger.log(context, "시트: 리다이렉트 초과 ($maxRedirects)")
    }
}
