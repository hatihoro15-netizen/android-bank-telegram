package com.banknotify.telegram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PaymentMethodItem(
    val name: String,
    val enabled: Boolean = true
)

data class MyAccountItem(
    val accountNumber: String,
    val accountName: String,
    val bankName: String = "",
    val memo: String = ""
)

data class WithdrawalPointItem(
    val accountName: String,
    val accountNumber: String = "",
    val bankName: String = "",
    val memo: String = ""
)

data class ZeropayBusinessItem(
    val businessName: String,
    val type: String  // "QR" 또는 "스캔"
)

class SettingsManager(context: Context) {

    private val prefs = context.getSharedPreferences("bank_notify_settings", Context.MODE_PRIVATE)

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, DEFAULT_BOT_TOKEN) ?: DEFAULT_BOT_TOKEN
        set(value) = prefs.edit().putString(KEY_BOT_TOKEN, value).apply()

    var depositChatId: String
        get() = prefs.getString(KEY_DEPOSIT_CHAT_ID, DEFAULT_DEPOSIT_CHAT_ID) ?: DEFAULT_DEPOSIT_CHAT_ID
        set(value) = prefs.edit().putString(KEY_DEPOSIT_CHAT_ID, value).apply()

    var withdrawalChatId: String
        get() = prefs.getString(KEY_WITHDRAWAL_CHAT_ID, DEFAULT_WITHDRAWAL_CHAT_ID) ?: DEFAULT_WITHDRAWAL_CHAT_ID
        set(value) = prefs.edit().putString(KEY_WITHDRAWAL_CHAT_ID, value).apply()

    var depositEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEPOSIT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DEPOSIT_ENABLED, value).apply()

    var withdrawalEnabled: Boolean
        get() = prefs.getBoolean(KEY_WITHDRAWAL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WITHDRAWAL_ENABLED, value).apply()

    var settlementTime: String
        get() = prefs.getString(KEY_SETTLEMENT_TIME, DEFAULT_SETTLEMENT_TIME) ?: DEFAULT_SETTLEMENT_TIME
        set(value) = prefs.edit().putString(KEY_SETTLEMENT_TIME, value).apply()

    val settlementHour: Int get() = settlementTime.split(":")[0].toIntOrNull() ?: 23
    val settlementMinute: Int get() = settlementTime.split(":").getOrNull(1)?.toIntOrNull() ?: 30

    var lastResetTimestamp: Long
        get() = prefs.getLong(KEY_LAST_RESET, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RESET, value).apply()

    var lastSettlementTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SETTLEMENT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SETTLEMENT, value).apply()

    // 디바이스 설정
    var deviceNumber: Int
        get() = prefs.getInt(KEY_DEVICE_NUMBER, 1)
        set(value) = prefs.edit().putInt(KEY_DEVICE_NUMBER, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    fun getDeviceLabel(): String {
        val name = deviceName
        return if (name.isNotBlank()) "${deviceNumber}번 - $name" else "${deviceNumber}번"
    }

    // 감지 방식 설정
    var smsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_ENABLED, value).apply()

    var pushEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PUSH_ENABLED, value).apply()

    // 로그 보관 기간
    var logRetentionDays: Int
        get() = prefs.getInt(KEY_LOG_RETENTION_DAYS, 30)
        set(value) = prefs.edit().putInt(KEY_LOG_RETENTION_DAYS, value).apply()

    // 내부거래 필터링
    var excludeInternalTransfers: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_INTERNAL, false)
        set(value) = prefs.edit().putBoolean(KEY_EXCLUDE_INTERNAL, value).apply()

    // 정산 전송 (이 기기에서 정산 메시지 전송 여부)
    var settlementEnabled: Boolean
        get() = prefs.getBoolean(KEY_SETTLEMENT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SETTLEMENT_ENABLED, value).apply()

    // 구글 시트 웹훅 URL
    var googleSheetUrl: String
        get() = prefs.getString(KEY_GOOGLE_SHEET_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_SHEET_URL, value).apply()


    // 출금장 계좌
    var withdrawalPointAccounts: List<WithdrawalPointItem>
        get() {
            val json = prefs.getString(KEY_WITHDRAWAL_POINTS, null) ?: return emptyList()
            return deserializeWithdrawalPoints(json)
        }
        set(value) = prefs.edit().putString(KEY_WITHDRAWAL_POINTS, serializeWithdrawalPoints(value)).apply()

    // 제로페이 업체
    var zeropayBusinesses: List<ZeropayBusinessItem>
        get() {
            val json = prefs.getString(KEY_ZEROPAY_BUSINESSES, null) ?: return emptyList()
            return deserializeZeropayBusinesses(json)
        }
        set(value) = prefs.edit().putString(KEY_ZEROPAY_BUSINESSES, serializeZeropayBusinesses(value)).apply()

    var myAccounts: List<MyAccountItem>
        get() {
            val json = prefs.getString(KEY_MY_ACCOUNTS, null) ?: return emptyList()
            return deserializeAccounts(json)
        }
        set(value) = prefs.edit().putString(KEY_MY_ACCOUNTS, serializeAccounts(value)).apply()

    var depositMethods: List<PaymentMethodItem>
        get() {
            val json = prefs.getString(KEY_DEPOSIT_METHODS, null) ?: return defaultDepositMethods()
            val list = deserializeMethods(json)
            if (list.isEmpty()) return defaultDepositMethods()
            return migrateDepositMethods(list)
        }
        set(value) = prefs.edit().putString(KEY_DEPOSIT_METHODS, serializeMethods(value)).apply()

    var withdrawalMethods: List<PaymentMethodItem>
        get() {
            val json = prefs.getString(KEY_WITHDRAWAL_METHODS, null) ?: return defaultWithdrawalMethods()
            val list = deserializeMethods(json)
            if (list.isEmpty()) return defaultWithdrawalMethods()
            return migrateWithdrawalMethods(list)
        }
        set(value) = prefs.edit().putString(KEY_WITHDRAWAL_METHODS, serializeMethods(value)).apply()

    private fun migrateDepositMethods(list: List<PaymentMethodItem>): List<PaymentMethodItem> {
        val migrated = list.toMutableList()
        var changed = false

        // "토스" → "토스송금"
        val tosIdx = migrated.indexOfFirst { it.name == "토스" }
        if (tosIdx >= 0) {
            migrated[tosIdx] = migrated[tosIdx].copy(name = "토스송금")
            changed = true
        }

        // "제로페이" → "제로페이(QR)" + "제로페이(스캔)" 추가
        val zpIdx = migrated.indexOfFirst { it.name == "제로페이" }
        if (zpIdx >= 0) {
            val enabled = migrated[zpIdx].enabled
            migrated[zpIdx] = PaymentMethodItem("제로페이(QR)", enabled)
            if (migrated.none { it.name == "제로페이(스캔)" }) {
                migrated.add(zpIdx + 1, PaymentMethodItem("제로페이(스캔)", enabled))
            }
            changed = true
        }

        // "네이버페이", "체크/카드" 제거 (입금에서 불필요)
        if (migrated.removeAll { it.name == "네이버페이" || it.name == "체크/카드" }) {
            changed = true
        }

        if (changed) {
            depositMethods = migrated
        }
        return migrated
    }

    private fun migrateWithdrawalMethods(list: List<PaymentMethodItem>): List<PaymentMethodItem> {
        val migrated = list.toMutableList()
        var changed = false

        // "페이코" → "페이코 출금"
        val paycoIdx = migrated.indexOfFirst { it.name == "페이코" }
        if (paycoIdx >= 0) {
            migrated[paycoIdx] = migrated[paycoIdx].copy(name = "페이코 출금")
            changed = true
        }

        // "제로페이" → "제로페이 환불"
        val zpIdx = migrated.indexOfFirst { it.name == "제로페이" }
        if (zpIdx >= 0) {
            migrated[zpIdx] = migrated[zpIdx].copy(name = "제로페이 환불")
            changed = true
        }

        // "토스", "카드결제" 제거 (출금에서 불필요)
        if (migrated.removeAll { it.name == "토스" || it.name == "카드결제" }) {
            changed = true
        }

        // "출금장" 없으면 추가
        if (migrated.none { it.name == "출금장" }) {
            val insertIdx = migrated.indexOfFirst { it.name == "계좌출금" }.let {
                if (it >= 0) it else migrated.size
            }
            migrated.add(insertIdx, PaymentMethodItem("출금장"))
            changed = true
        }

        if (changed) {
            withdrawalMethods = migrated
        }
        return migrated
    }

    fun getEnabledDepositMethodNames(): List<String> =
        depositMethods.filter { it.enabled }.map { it.name }

    fun getEnabledWithdrawalMethodNames(): List<String> =
        withdrawalMethods.filter { it.enabled }.map { it.name }

    companion object {
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_DEPOSIT_CHAT_ID = "deposit_chat_id"
        private const val KEY_WITHDRAWAL_CHAT_ID = "withdrawal_chat_id"
        private const val KEY_DEPOSIT_ENABLED = "deposit_enabled"
        private const val KEY_WITHDRAWAL_ENABLED = "withdrawal_enabled"
        private const val KEY_SETTLEMENT_TIME = "settlement_time"
        private const val KEY_LAST_RESET = "last_reset_timestamp"
        private const val KEY_LAST_SETTLEMENT = "last_settlement_timestamp"
        private const val KEY_DEPOSIT_METHODS = "deposit_methods"
        private const val KEY_WITHDRAWAL_METHODS = "withdrawal_methods"
        private const val KEY_DEVICE_NUMBER = "device_number"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_LOG_RETENTION_DAYS = "log_retention_days"
        private const val KEY_SMS_ENABLED = "sms_detection_enabled"
        private const val KEY_PUSH_ENABLED = "push_detection_enabled"
        private const val KEY_EXCLUDE_INTERNAL = "exclude_internal_transfers"
        private const val KEY_MY_ACCOUNTS = "my_accounts"
        private const val KEY_SETTLEMENT_ENABLED = "settlement_enabled"
        private const val KEY_GOOGLE_SHEET_URL = "google_sheet_url"
        private const val KEY_WITHDRAWAL_POINTS = "withdrawal_point_accounts"
        private const val KEY_ZEROPAY_BUSINESSES = "zeropay_businesses"

        const val DEFAULT_BOT_TOKEN = "8322174657:AAHVc_-YECvt3vnqmAieSIkQ57X7qwFKEU4"
        const val DEFAULT_DEPOSIT_CHAT_ID = "-5120830461"
        const val DEFAULT_WITHDRAWAL_CHAT_ID = "-5228915149"
        const val DEFAULT_SETTLEMENT_TIME = "23:30"

        fun defaultDepositMethods() = listOf(
            PaymentMethodItem("카카오페이"),
            PaymentMethodItem("연락처송금"),
            PaymentMethodItem("토스송금"),
            PaymentMethodItem("제로페이(QR)"),
            PaymentMethodItem("제로페이(스캔)"),
            PaymentMethodItem("계좌이체"),
            PaymentMethodItem("기타")
        )

        fun defaultWithdrawalMethods() = listOf(
            PaymentMethodItem("카카오페이"),
            PaymentMethodItem("네이버페이"),
            PaymentMethodItem("페이코 출금"),
            PaymentMethodItem("제로페이 환불"),
            PaymentMethodItem("출금장"),
            PaymentMethodItem("계좌출금"),
            PaymentMethodItem("기타")
        )

        fun serializeMethods(methods: List<PaymentMethodItem>): String {
            val arr = JSONArray()
            methods.forEach {
                arr.put(JSONObject().apply {
                    put("name", it.name)
                    put("enabled", it.enabled)
                })
            }
            return arr.toString()
        }

        fun deserializeMethods(json: String): List<PaymentMethodItem> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    PaymentMethodItem(obj.getString("name"), obj.optBoolean("enabled", true))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun serializeAccounts(accounts: List<MyAccountItem>): String {
            val arr = JSONArray()
            accounts.forEach {
                arr.put(JSONObject().apply {
                    put("accountNumber", it.accountNumber)
                    put("accountName", it.accountName)
                    put("bankName", it.bankName)
                    put("memo", it.memo)
                })
            }
            return arr.toString()
        }

        fun deserializeAccounts(json: String): List<MyAccountItem> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    MyAccountItem(
                        accountNumber = obj.optString("accountNumber", ""),
                        accountName = obj.optString("accountName", ""),
                        bankName = obj.optString("bankName", ""),
                        memo = obj.optString("memo", "")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun serializeWithdrawalPoints(items: List<WithdrawalPointItem>): String {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("accountName", it.accountName)
                    put("accountNumber", it.accountNumber)
                    put("bankName", it.bankName)
                    put("memo", it.memo)
                })
            }
            return arr.toString()
        }

        fun deserializeWithdrawalPoints(json: String): List<WithdrawalPointItem> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    WithdrawalPointItem(
                        accountName = obj.optString("accountName", ""),
                        accountNumber = obj.optString("accountNumber", ""),
                        bankName = obj.optString("bankName", ""),
                        memo = obj.optString("memo", "")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun serializeZeropayBusinesses(items: List<ZeropayBusinessItem>): String {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("businessName", it.businessName)
                    put("type", it.type)
                })
            }
            return arr.toString()
        }

        fun deserializeZeropayBusinesses(json: String): List<ZeropayBusinessItem> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ZeropayBusinessItem(
                        businessName = obj.optString("businessName", ""),
                        type = obj.optString("type", "QR")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun isInternalTransfer(notification: BankNotification, myAccounts: List<MyAccountItem>): Boolean {
            if (myAccounts.isEmpty()) {
                android.util.Log.d("InternalFilter", "myAccounts is empty, skipping")
                return false
            }
            val senderName = notification.senderName ?: ""
            val accountInfo = notification.accountInfo ?: ""
            val originalText = notification.originalText

            android.util.Log.d("InternalFilter", "Checking: bank=${notification.bankName}, sender=$senderName, amount=${notification.amount}, account=$accountInfo, accounts=${myAccounts.size}")

            val hasDetailedInfo = (notification.amount != null || senderName.isNotBlank())

            return myAccounts.any { account ->
                val hasBankFilter = account.bankName.isNotBlank()
                val bankMatches = hasBankFilter &&
                    notification.bankName.trim() == account.bankName.trim()

                // 1. 정보 없는 일반 알림 + 은행 일치 → 차단 (예: "돈이 입금됐어요")
                val genericBankMatch = hasBankFilter && bankMatches && !hasDetailedInfo

                // 2. 이름 매칭 (은행 등록 시 은행+이름 조합, 동명이인 구분)
                val nameFound = account.accountName.isNotBlank() && senderName.isNotBlank() &&
                    senderName.contains(account.accountName)
                val nameMatch = if (hasBankFilter) nameFound && bankMatches else nameFound

                // 3. 계좌번호 매칭 (가장 정확, 단독 매칭)
                val accountMatch = account.accountNumber.isNotBlank() && accountInfo.isNotBlank() &&
                    accountInfo.replace("-", "").replace("*", "").contains(
                        account.accountNumber.replace("-", "").replace("*", ""))

                // 4. 원본 텍스트에서 이름 검색 (은행 조합)
                val textNameFound = account.accountName.isNotBlank() &&
                    originalText.contains(account.accountName)
                val textNameMatch = if (hasBankFilter) textNameFound && bankMatches else textNameFound

                // 5. 원본 텍스트에서 계좌번호 검색 (단독 매칭)
                val textAccountMatch = account.accountNumber.isNotBlank() &&
                    originalText.replace("-", "").replace("*", "").contains(
                        account.accountNumber.replace("-", "").replace("*", ""))

                val matched = genericBankMatch || nameMatch || accountMatch || textNameMatch || textAccountMatch
                android.util.Log.d("InternalFilter", "Account[bank=${account.bankName},name=${account.accountName}] → generic=$genericBankMatch, name=$nameMatch, account=$accountMatch, textName=$textNameMatch, textAccount=$textAccountMatch → $matched")
                matched
            }
        }
    }
}
