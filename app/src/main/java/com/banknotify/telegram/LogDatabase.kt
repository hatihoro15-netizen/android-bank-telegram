package com.banknotify.telegram

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val bankName: String,
    val amount: String,
    val senderName: String,
    val accountInfo: String,
    val originalText: String,
    val packageName: String,
    val sentToTelegram: Boolean,
    val transactionType: String,
    val paymentMethod: String,
    val deviceNumber: Int = 0,
    val deviceName: String = "",
    val transactionStatus: String = "정상",
    val ignoreReason: String = "",
    val telegramStatus: String = "성공",
    val retryCount: Int = 0,
    val source: String = "알림"
)

class LogDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_LOGS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_BANK_NAME TEXT NOT NULL,
                $COL_AMOUNT TEXT,
                $COL_SENDER_NAME TEXT,
                $COL_ACCOUNT_INFO TEXT,
                $COL_ORIGINAL_TEXT TEXT NOT NULL,
                $COL_PACKAGE_NAME TEXT NOT NULL,
                $COL_SENT_TO_TELEGRAM INTEGER DEFAULT 0,
                $COL_TRANSACTION_TYPE TEXT DEFAULT '',
                $COL_PAYMENT_METHOD TEXT DEFAULT '',
                $COL_DEVICE_NUMBER INTEGER DEFAULT 0,
                $COL_DEVICE_NAME TEXT DEFAULT '',
                $COL_TRANSACTION_STATUS TEXT DEFAULT '정상',
                $COL_IGNORE_REASON TEXT DEFAULT '',
                $COL_TELEGRAM_STATUS TEXT DEFAULT '성공',
                $COL_RETRY_COUNT INTEGER DEFAULT 0,
                $COL_SOURCE TEXT DEFAULT '알림'
            )
        """)
        db.execSQL("CREATE INDEX idx_logs_timestamp ON $TABLE_LOGS($COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_logs_telegram_status ON $TABLE_LOGS($COL_TELEGRAM_STATUS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 4 && newVersion == 5) {
            db.execSQL("ALTER TABLE $TABLE_LOGS ADD COLUMN $COL_SOURCE TEXT DEFAULT '알림'")
        } else {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
            onCreate(db)
        }
    }

    fun insertLog(
        notification: BankNotification,
        sentToTelegram: Boolean,
        deviceNumber: Int = 0,
        deviceName: String = "",
        telegramStatus: String = if (sentToTelegram) "성공" else "실패",
        ignoreReason: String = "",
        source: String = notification.source
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, notification.timestamp)
            put(COL_BANK_NAME, notification.bankName)
            put(COL_AMOUNT, notification.amount ?: "")
            put(COL_SENDER_NAME, notification.senderName ?: "")
            put(COL_ACCOUNT_INFO, notification.accountInfo ?: "")
            put(COL_ORIGINAL_TEXT, notification.originalText)
            put(COL_PACKAGE_NAME, notification.packageName)
            put(COL_SENT_TO_TELEGRAM, if (sentToTelegram) 1 else 0)
            put(COL_TRANSACTION_TYPE, notification.transactionType.label)
            put(COL_PAYMENT_METHOD, notification.paymentMethod)
            put(COL_DEVICE_NUMBER, deviceNumber)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_TRANSACTION_STATUS, notification.transactionStatus.label)
            put(COL_IGNORE_REASON, ignoreReason)
            put(COL_TELEGRAM_STATUS, telegramStatus)
            put(COL_RETRY_COUNT, 0)
            put(COL_SOURCE, source)
        }
        return db.insert(TABLE_LOGS, null, values)
    }

    fun insertIgnoredLog(
        packageName: String,
        bankName: String,
        originalText: String,
        ignoreReason: String,
        deviceNumber: Int,
        deviceName: String,
        source: String = "알림"
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_BANK_NAME, bankName)
            put(COL_AMOUNT, "")
            put(COL_SENDER_NAME, "")
            put(COL_ACCOUNT_INFO, "")
            put(COL_ORIGINAL_TEXT, originalText)
            put(COL_PACKAGE_NAME, packageName)
            put(COL_SENT_TO_TELEGRAM, 0)
            put(COL_TRANSACTION_TYPE, "")
            put(COL_PAYMENT_METHOD, "")
            put(COL_DEVICE_NUMBER, deviceNumber)
            put(COL_DEVICE_NAME, deviceName)
            put(COL_TRANSACTION_STATUS, "무시")
            put(COL_IGNORE_REASON, ignoreReason)
            put(COL_TELEGRAM_STATUS, "")
            put(COL_RETRY_COUNT, 0)
            put(COL_SOURCE, source)
        }
        return db.insert(TABLE_LOGS, null, values)
    }

    fun updateTelegramStatus(logId: Long, status: String, retryCount: Int = 0) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TELEGRAM_STATUS, status)
            put(COL_SENT_TO_TELEGRAM, if (status == "성공") 1 else 0)
            put(COL_RETRY_COUNT, retryCount)
        }
        db.update(TABLE_LOGS, values, "$COL_ID = ?", arrayOf(logId.toString()))
    }

    fun getAllLogs(): List<LogEntry> {
        return queryLogs(null, null, "500")
    }

    fun getFilteredLogs(
        filter: String = "전체",
        searchQuery: String = "",
        deviceNumber: Int = 0
    ): List<LogEntry> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        when (filter) {
            "정상" -> conditions.add("$COL_TRANSACTION_STATUS = '정상'")
            "실패취소" -> conditions.add("$COL_TRANSACTION_STATUS IN ('실패', '취소')")
            "무시" -> conditions.add("$COL_TRANSACTION_STATUS = '무시'")
        }

        if (deviceNumber > 0) {
            conditions.add("$COL_DEVICE_NUMBER = ?")
            args.add(deviceNumber.toString())
        }

        if (searchQuery.isNotBlank()) {
            conditions.add("($COL_AMOUNT LIKE ? OR $COL_SENDER_NAME LIKE ? OR $COL_ORIGINAL_TEXT LIKE ? OR $COL_BANK_NAME LIKE ?)")
            val like = "%$searchQuery%"
            args.addAll(listOf(like, like, like, like))
        }

        val selection = if (conditions.isNotEmpty()) conditions.joinToString(" AND ") else null
        val selectionArgs = if (args.isNotEmpty()) args.toTypedArray() else null

        return queryLogs(selection, selectionArgs, "500")
    }

    fun getPendingLogs(): List<LogEntry> {
        return queryLogs("$COL_TELEGRAM_STATUS IN ('대기', '실패') AND $COL_TRANSACTION_STATUS != '무시'", null, null)
    }

    fun getFailedLogs(): List<LogEntry> {
        return queryLogs(
            "$COL_TELEGRAM_STATUS IN ('실패', '대기') AND $COL_TRANSACTION_STATUS != '무시'",
            null, "500"
        )
    }

    fun getPendingCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_LOGS WHERE $COL_TELEGRAM_STATUS IN ('대기', '실패') AND $COL_TRANSACTION_STATUS != '무시'",
            null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getLogsSince(timestamp: Long): List<LogEntry> {
        return queryLogs("$COL_TIMESTAMP > ?", arrayOf(timestamp.toString()), null)
    }

    fun getLogsSinceByType(timestamp: Long, transactionType: String): List<LogEntry> {
        return queryLogs(
            "$COL_TIMESTAMP > ? AND $COL_TRANSACTION_TYPE = ?",
            arrayOf(timestamp.toString(), transactionType),
            null
        )
    }

    fun getLogsSinceByTypeAndStatus(timestamp: Long, transactionType: String): List<LogEntry> {
        return queryLogs(
            "$COL_TIMESTAMP > ? AND $COL_TRANSACTION_TYPE = ? AND $COL_TRANSACTION_STATUS != '무시'",
            arrayOf(timestamp.toString(), transactionType),
            null
        )
    }

    fun deleteOldLogs(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
        writableDatabase.delete(TABLE_LOGS, "$COL_TIMESTAMP < ?", arrayOf(cutoff.toString()))
    }

    private fun queryLogs(selection: String?, selectionArgs: Array<String>?, limit: String?): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LOGS, null, selection, selectionArgs, null, null,
            "$COL_TIMESTAMP DESC", limit
        )
        cursor.use {
            while (it.moveToNext()) {
                logs.add(
                    LogEntry(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        bankName = it.getString(it.getColumnIndexOrThrow(COL_BANK_NAME)),
                        amount = it.getString(it.getColumnIndexOrThrow(COL_AMOUNT)),
                        senderName = it.getString(it.getColumnIndexOrThrow(COL_SENDER_NAME)),
                        accountInfo = it.getString(it.getColumnIndexOrThrow(COL_ACCOUNT_INFO)),
                        originalText = it.getString(it.getColumnIndexOrThrow(COL_ORIGINAL_TEXT)),
                        packageName = it.getString(it.getColumnIndexOrThrow(COL_PACKAGE_NAME)),
                        sentToTelegram = it.getInt(it.getColumnIndexOrThrow(COL_SENT_TO_TELEGRAM)) == 1,
                        transactionType = it.getString(it.getColumnIndexOrThrow(COL_TRANSACTION_TYPE)),
                        paymentMethod = it.getString(it.getColumnIndexOrThrow(COL_PAYMENT_METHOD)),
                        deviceNumber = it.getInt(it.getColumnIndexOrThrow(COL_DEVICE_NUMBER)),
                        deviceName = it.getString(it.getColumnIndexOrThrow(COL_DEVICE_NAME)),
                        transactionStatus = it.getString(it.getColumnIndexOrThrow(COL_TRANSACTION_STATUS)),
                        ignoreReason = it.getString(it.getColumnIndexOrThrow(COL_IGNORE_REASON)),
                        telegramStatus = it.getString(it.getColumnIndexOrThrow(COL_TELEGRAM_STATUS)),
                        retryCount = it.getInt(it.getColumnIndexOrThrow(COL_RETRY_COUNT)),
                        source = it.getString(it.getColumnIndexOrThrow(COL_SOURCE)) ?: "알림"
                    )
                )
            }
        }
        return logs
    }

    fun clearLogs() {
        writableDatabase.delete(TABLE_LOGS, null, null)
    }

    companion object {
        private const val DB_NAME = "notification_log.db"
        private const val DB_VERSION = 5
        private const val TABLE_LOGS = "logs"
        private const val COL_ID = "id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_BANK_NAME = "bank_name"
        private const val COL_AMOUNT = "amount"
        private const val COL_SENDER_NAME = "sender_name"
        private const val COL_ACCOUNT_INFO = "account_info"
        private const val COL_ORIGINAL_TEXT = "original_text"
        private const val COL_PACKAGE_NAME = "package_name"
        private const val COL_SENT_TO_TELEGRAM = "sent_to_telegram"
        private const val COL_TRANSACTION_TYPE = "transaction_type"
        private const val COL_PAYMENT_METHOD = "payment_method"
        private const val COL_DEVICE_NUMBER = "device_number"
        private const val COL_DEVICE_NAME = "device_name"
        private const val COL_TRANSACTION_STATUS = "transaction_status"
        private const val COL_IGNORE_REASON = "ignore_reason"
        private const val COL_TELEGRAM_STATUS = "telegram_status"
        private const val COL_RETRY_COUNT = "retry_count"
        private const val COL_SOURCE = "source"
    }
}
