package com.banknotify.telegram

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {

    private lateinit var logDb: LogDatabase
    private lateinit var settings: SettingsManager
    private lateinit var adapter: LogAdapter
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var spinnerDeviceFilter: Spinner

    private var currentFilter = "전체"
    private var currentSearch = ""
    private var currentDeviceNumber = 0  // 0 = 전체

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        supportActionBar?.apply {
            title = "전체 로그"
            setDisplayHomeAsUpEnabled(true)
        }

        logDb = LogDatabase(this)
        settings = SettingsManager(this)
        rvLogs = findViewById(R.id.rvLogs)
        tvEmpty = findViewById(R.id.tvEmpty)
        etSearch = findViewById(R.id.etSearch)
        chipGroup = findViewById(R.id.chipGroup)
        spinnerDeviceFilter = findViewById(R.id.spinnerDeviceFilter)

        adapter = LogAdapter(emptyList()) { log -> resendLog(log) }
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = adapter

        // 디바이스 필터 드롭다운 설정
        setupDeviceFilter()

        // 필터 칩 설정
        setupFilterChips()

        // 검색
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearch = s?.toString()?.trim() ?: ""
                loadLogs()
            }
        })

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("기록 삭제")
                .setMessage("모든 알림 기록을 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    logDb.clearLogs()
                    loadLogs()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        loadLogs()
    }

    private fun setupDeviceFilter() {
        val deviceOptions = mutableListOf("전체 디바이스")
        deviceOptions.addAll((1..100).map { "${it}번" })
        spinnerDeviceFilter.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, deviceOptions)

        spinnerDeviceFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentDeviceNumber = if (position == 0) 0 else position
                loadLogs()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFilterChips() {
        val filters = listOf("전체", "정상", "실패취소", "무시")
        val labels = listOf("전체", "정상", "실패·취소", "무시")

        filters.forEachIndexed { index, filter ->
            val chip = Chip(this).apply {
                text = labels[index]
                isCheckable = true
                isChecked = filter == currentFilter
                setOnClickListener {
                    currentFilter = filter
                    loadLogs()
                    for (i in 0 until chipGroup.childCount) {
                        val c = chipGroup.getChildAt(i) as? Chip
                        c?.isChecked = c?.tag == filter
                    }
                }
                tag = filter
            }
            chipGroup.addView(chip)
        }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun loadLogs() {
        val logs = logDb.getFilteredLogs(currentFilter, currentSearch, currentDeviceNumber)
        adapter.updateLogs(logs)

        if (logs.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvLogs.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvLogs.visibility = View.VISIBLE
        }
    }

    private fun resendLog(log: LogEntry) {
        val botToken = settings.botToken
        val chatId = when (log.transactionType) {
            "입금" -> settings.depositChatId
            "출금" -> settings.withdrawalChatId
            else -> ""
        }

        if (botToken.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "봇 토큰 또는 Chat ID가 설정되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "재전송 중...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val sender = TelegramSender()
            val deviceLabel = if (log.deviceName.isNotBlank()) {
                "${log.deviceNumber}번 - ${log.deviceName}"
            } else "${log.deviceNumber}번"

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
            val message = sender.formatBankNotification(notification, deviceLabel)
            val (success, _) = sender.sendWithRetry(botToken, chatId, message)

            withContext(Dispatchers.Main) {
                if (success) {
                    logDb.updateTelegramStatus(log.id, "성공")
                    Toast.makeText(this@LogActivity, "재전송 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@LogActivity, "재전송 실패", Toast.LENGTH_SHORT).show()
                }
                loadLogs()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
