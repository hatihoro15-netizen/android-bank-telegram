package com.banknotify.telegram

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val PREF_NAME = "debug_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    fun log(context: Context, message: String) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_ENTRIES, "") ?: ""
            val lines = existing.split("\n").filter { it.isNotBlank() }.toMutableList()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            lines.add(0, "[$ts] $message")
            prefs.edit().putString(KEY_ENTRIES, lines.take(MAX_ENTRIES).joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    fun getAll(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ENTRIES, "로그 없음") ?: "로그 없음"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
