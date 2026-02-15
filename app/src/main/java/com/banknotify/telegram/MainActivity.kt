package com.banknotify.telegram

import android.Manifest
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var logDb: LogDatabase

    private lateinit var tvServiceStatus: TextView
    private lateinit var switchDeposit: MaterialSwitch
    private lateinit var switchWithdrawal: MaterialSwitch
    private lateinit var containerDepositMethods: LinearLayout
    private lateinit var containerWithdrawalMethods: LinearLayout
    private lateinit var etBotToken: EditText
    private lateinit var etDepositChatId: EditText
    private lateinit var etWithdrawalChatId: EditText
    private lateinit var tvSettlementTime: TextView
    private lateinit var spinnerDeviceNumber: Spinner
    private lateinit var etDeviceName: EditText
    private lateinit var tvPendingCount: TextView
    private lateinit var switchSmsDetection: MaterialSwitch
    private lateinit var switchPushDetection: MaterialSwitch
    private lateinit var tvSmsStatus: TextView
    private lateinit var tvPushStatus: TextView
    private lateinit var switchExcludeInternal: MaterialSwitch
    private lateinit var switchSettlementEnabled: MaterialSwitch
    private lateinit var containerMyAccounts: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager(this)
        logDb = LogDatabase(this)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        switchDeposit = findViewById(R.id.switchDeposit)
        switchWithdrawal = findViewById(R.id.switchWithdrawal)
        containerDepositMethods = findViewById(R.id.containerDepositMethods)
        containerWithdrawalMethods = findViewById(R.id.containerWithdrawalMethods)
        etBotToken = findViewById(R.id.etBotToken)
        etDepositChatId = findViewById(R.id.etDepositChatId)
        etWithdrawalChatId = findViewById(R.id.etWithdrawalChatId)
        tvSettlementTime = findViewById(R.id.tvSettlementTime)
        spinnerDeviceNumber = findViewById(R.id.spinnerDeviceNumber)
        etDeviceName = findViewById(R.id.etDeviceName)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        switchSmsDetection = findViewById(R.id.switchSmsDetection)
        switchPushDetection = findViewById(R.id.switchPushDetection)
        tvSmsStatus = findViewById(R.id.tvSmsStatus)
        tvPushStatus = findViewById(R.id.tvPushStatus)
        switchExcludeInternal = findViewById(R.id.switchExcludeInternal)
        switchSettlementEnabled = findViewById(R.id.switchSettlementEnabled)
        containerMyAccounts = findViewById(R.id.containerMyAccounts)

        // 포그라운드 서비스 시작 + 배터리 최적화 해제 요청
        KeepAliveService.start(this)
        requestBatteryOptimizationExemption()

        // 자동 업데이트 체크
        AppUpdater(this).checkAndUpdate()

        // 내부거래 제외 토글
        switchExcludeInternal.setOnCheckedChangeListener { _, checked ->
            settings.excludeInternalTransfers = checked
        }
        // 정산 전송 토글
        switchSettlementEnabled.setOnCheckedChangeListener { _, checked ->
            settings.settlementEnabled = checked
        }
        findViewById<Button>(R.id.btnAddAccount).setOnClickListener {
            showAddAccountDialog()
        }
        findViewById<Button>(R.id.btnDiagnoseFilter).setOnClickListener {
            showFilterDiagnostic()
        }

        // 감지 방식 토글
        switchSmsDetection.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasSmsPermission()) {
                switchSmsDetection.isChecked = false
                requestSmsPermission()
                return@setOnCheckedChangeListener
            }
            settings.smsEnabled = checked
            updateDetectionStatus()
        }
        switchPushDetection.setOnCheckedChangeListener { _, checked ->
            settings.pushEnabled = checked
            updateDetectionStatus()
        }
        findViewById<Button>(R.id.btnRequestSmsPermission).setOnClickListener {
            requestSmsPermission()
        }

        // 디바이스 번호 스피너 (1~100)
        val deviceNumbers = (1..100).map { "${it}번" }
        spinnerDeviceNumber.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, deviceNumbers)
        spinnerDeviceNumber.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settings.deviceNumber = position + 1
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        etDeviceName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                settings.deviceName = s?.toString()?.trim() ?: ""
            }
        })

        findViewById<Button>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnAddDepositMethod).setOnClickListener {
            showAddMethodDialog(isDeposit = true)
        }

        findViewById<Button>(R.id.btnAddWithdrawalMethod).setOnClickListener {
            showAddMethodDialog(isDeposit = false)
        }

        findViewById<Button>(R.id.btnChangeSettlementTime).setOnClickListener {
            showTimePicker()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.btnTestDeposit).setOnClickListener {
            sendTestMessage(isDeposit = true)
        }

        findViewById<Button>(R.id.btnTestWithdrawal).setOnClickListener {
            sendTestMessage(isDeposit = false)
        }

        findViewById<Button>(R.id.btnViewLogs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        findViewById<Button>(R.id.btnDebugLog).setOnClickListener {
            val log = DebugLogger.getAll(this)
            android.app.AlertDialog.Builder(this)
                .setTitle("알림 처리 로그")
                .setMessage(log)
                .setPositiveButton("확인", null)
                .setNeutralButton("초기화") { _, _ ->
                    DebugLogger.clear(this)
                    android.widget.Toast.makeText(this, "로그 초기화됨", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        findViewById<Button>(R.id.btnProcessPending).setOnClickListener {
            processPendingNow()
        }

        // 테스트 알림 버튼
        findViewById<Button>(R.id.btnTestNotifyDeposit).setOnClickListener {
            sendTestNotification("deposit")
        }
        findViewById<Button>(R.id.btnTestNotifyWithdrawal).setOnClickListener {
            sendTestNotification("withdrawal")
        }
        findViewById<Button>(R.id.btnTestNotifyDepositFailed).setOnClickListener {
            sendTestNotification("deposit_failed")
        }
        findViewById<Button>(R.id.btnTestNotifyWithdrawalFailed).setOnClickListener {
            sendTestNotification("withdrawal_failed")
        }

        // SMS 테스트 버튼
        findViewById<Button>(R.id.btnTestSmsDeposit).setOnClickListener {
            sendTestSmsNotification(isDeposit = true)
        }
        findViewById<Button>(R.id.btnTestSmsWithdrawal).setOnClickListener {
            sendTestSmsNotification(isDeposit = false)
        }

        loadSettings()

        // 워치독 + 정산 스케줄 시작
        SettlementScheduler.scheduleDaily(this)
        SettlementScheduler.scheduleWatchdog(this)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updatePendingCount()
        updateDetectionStatus()
    }

    private fun loadSettings() {
        etBotToken.setText(settings.botToken)
        etDepositChatId.setText(settings.depositChatId)
        etWithdrawalChatId.setText(settings.withdrawalChatId)
        switchDeposit.isChecked = settings.depositEnabled
        switchWithdrawal.isChecked = settings.withdrawalEnabled
        tvSettlementTime.text = settings.settlementTime

        // 디바이스 설정
        spinnerDeviceNumber.setSelection(settings.deviceNumber - 1)
        etDeviceName.setText(settings.deviceName)

        // 감지 방식 설정
        switchSmsDetection.isChecked = settings.smsEnabled
        switchPushDetection.isChecked = settings.pushEnabled

        // 내부거래 설정
        switchExcludeInternal.isChecked = settings.excludeInternalTransfers

        // 정산 설정
        switchSettlementEnabled.isChecked = settings.settlementEnabled
        buildAccountViews()

        refreshMethodViews()
    }

    private fun refreshMethodViews() {
        buildMethodViews(containerDepositMethods, settings.depositMethods, isDeposit = true)
        buildMethodViews(containerWithdrawalMethods, settings.withdrawalMethods, isDeposit = false)
    }

    private val methodIcons = mapOf(
        "계좌이체" to "\uD83C\uDFE6", "계좌출금" to "\uD83C\uDFE6",
        "카카오페이" to "\uD83D\uDFE1", "네이버페이" to "\uD83D\uDFE2",
        "토스" to "\uD83D\uDD35", "페이코" to "\uD83D\uDD34",
        "제로페이" to "\uD83D\uDFE3", "연락처송금" to "\uD83D\uDCDE",
        "체크/카드" to "\uD83D\uDCB3", "카드결제" to "\uD83D\uDCB3",
        "삼성페이" to "\u2B1B", "기타" to "\u2699\uFE0F"
    )

    private fun getMethodIcon(name: String): String {
        return methodIcons[name] ?: "\uD83D\uDCB1"
    }

    private fun buildMethodViews(
        container: LinearLayout,
        methods: List<PaymentMethodItem>,
        isDeposit: Boolean
    ) {
        container.removeAllViews()

        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val checkboxTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primaryColor, Color.parseColor("#9E9E9E"))
        )

        // 전체 선택/해제 토글
        val selectAllRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val allChecked = methods.all { it.enabled }
        val selectAllCheckbox = CheckBox(this).apply {
            text = "전체 선택"
            isChecked = allChecked
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#616161"))
            setTypeface(null, Typeface.BOLD)
            buttonTintList = checkboxTint
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        selectAllCheckbox.setOnCheckedChangeListener { _, checked ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child.tag == "method_row") {
                    val row = child as LinearLayout
                    val cb = row.findViewWithTag<CheckBox>("method_checkbox")
                    cb?.isChecked = checked
                }
            }
        }
        selectAllRow.addView(selectAllCheckbox)
        container.addView(selectAllRow)

        container.addView(createDivider())

        methods.forEachIndexed { index, method ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(10), dpToPx(4), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tag = "method_row"
            }

            val iconView = TextView(this).apply {
                text = getMethodIcon(method.name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, 0, dpToPx(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val checkbox = CheckBox(this).apply {
                text = method.name
                isChecked = method.enabled
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#212121"))
                buttonTintList = checkboxTint
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                this.tag = "method_checkbox"
            }

            val deleteBtn = TextView(this).apply {
                text = "\uD83D\uDDD1\uFE0F"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dpToPx(12), dpToPx(6), dpToPx(8), dpToPx(6))
                setOnClickListener {
                    showDeleteMethodDialog(method.name, isDeposit)
                }
            }

            row.addView(iconView)
            row.addView(checkbox)
            row.addView(deleteBtn)
            container.addView(row)

            if (index < methods.size - 1) {
                container.addView(createDivider())
            }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                setMargins(dpToPx(8), 0, dpToPx(8), 0)
            }
        }
    }

    private fun showAddMethodDialog(isDeposit: Boolean) {
        val typeLabel = if (isDeposit) "입금" else "출금"
        val editText = EditText(this).apply {
            hint = "수신 방식 이름 (예: 삼성페이)"
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }

        AlertDialog.Builder(this)
            .setTitle("$typeLabel 수신 방식 추가")
            .setView(editText)
            .setPositiveButton("추가") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addMethod(name, isDeposit)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun addMethod(name: String, isDeposit: Boolean) {
        if (isDeposit) {
            val current = settings.depositMethods.toMutableList()
            if (current.any { it.name == name }) {
                Toast.makeText(this, "이미 존재하는 수신 방식입니다", Toast.LENGTH_SHORT).show()
                return
            }
            val otherIndex = current.indexOfFirst { it.name == "기타" }
            if (otherIndex >= 0) current.add(otherIndex, PaymentMethodItem(name))
            else current.add(PaymentMethodItem(name))
            settings.depositMethods = current
        } else {
            val current = settings.withdrawalMethods.toMutableList()
            if (current.any { it.name == name }) {
                Toast.makeText(this, "이미 존재하는 수신 방식입니다", Toast.LENGTH_SHORT).show()
                return
            }
            val otherIndex = current.indexOfFirst { it.name == "기타" }
            if (otherIndex >= 0) current.add(otherIndex, PaymentMethodItem(name))
            else current.add(PaymentMethodItem(name))
            settings.withdrawalMethods = current
        }
        refreshMethodViews()
        Toast.makeText(this, "추가되었습니다", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteMethodDialog(name: String, isDeposit: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("'$name' 수신 방식을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                if (isDeposit) {
                    settings.depositMethods = settings.depositMethods.filter { it.name != name }
                } else {
                    settings.withdrawalMethods = settings.withdrawalMethods.filter { it.name != name }
                }
                refreshMethodViews()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, hour, minute ->
            val timeStr = String.format("%02d:%02d", hour, minute)
            tvSettlementTime.text = timeStr
            settings.settlementTime = timeStr
            SettlementScheduler.scheduleDaily(this)
            Toast.makeText(this, "정산 시간: $timeStr (데이터 초기화: 23:55)", Toast.LENGTH_SHORT).show()
        }, settings.settlementHour, settings.settlementMinute, true).show()
    }

    private fun saveSettings() {
        val token = etBotToken.text.toString().trim()
        if (token.isBlank()) {
            Toast.makeText(this, "봇 토큰을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        settings.botToken = token
        settings.depositChatId = etDepositChatId.text.toString().trim()
        settings.withdrawalChatId = etWithdrawalChatId.text.toString().trim()
        settings.depositEnabled = switchDeposit.isChecked
        settings.withdrawalEnabled = switchWithdrawal.isChecked

        // 디바이스 설정
        settings.deviceNumber = spinnerDeviceNumber.selectedItemPosition + 1
        settings.deviceName = etDeviceName.text.toString().trim()

        // 체크박스 상태 저장
        saveMethodCheckboxes(containerDepositMethods, isDeposit = true)
        saveMethodCheckboxes(containerWithdrawalMethods, isDeposit = false)

        // 정산 알람 스케줄
        SettlementScheduler.scheduleDaily(this)
        SettlementScheduler.scheduleWatchdog(this)

        Toast.makeText(this, "설정이 저장되었습니다", Toast.LENGTH_SHORT).show()
    }

    private fun saveMethodCheckboxes(container: LinearLayout, isDeposit: Boolean) {
        val updated = mutableListOf<PaymentMethodItem>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag != "method_row") continue
            val row = child as? LinearLayout ?: continue
            val checkbox = row.findViewWithTag<CheckBox>("method_checkbox") ?: continue
            val methodName = checkbox.text.toString()
            updated.add(PaymentMethodItem(methodName, checkbox.isChecked))
        }
        if (isDeposit) settings.depositMethods = updated
        else settings.withdrawalMethods = updated
    }

    private fun updateServiceStatus() {
        val enabled = isNotificationServiceEnabled()
        tvServiceStatus.text = if (enabled) "알림 감지 서비스: 활성화됨" else "알림 감지 서비스: 비활성화됨"
        tvServiceStatus.setTextColor(
            getColor(if (enabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        if (enabled) {
            NotificationHelper.clearServiceStoppedNotification(this)
        }
    }

    private fun updatePendingCount() {
        val count = logDb.getPendingCount()
        if (count > 0) {
            tvPendingCount.text = "대기/실패 알림: ${count}건"
            tvPendingCount.visibility = View.VISIBLE
            findViewById<Button>(R.id.btnProcessPending).visibility = View.VISIBLE
        } else {
            tvPendingCount.text = "대기 중인 알림 없음"
            tvPendingCount.visibility = View.VISIBLE
            findViewById<Button>(R.id.btnProcessPending).visibility = View.GONE
        }
    }

    private fun processPendingNow() {
        if (!NetworkMonitor.isNetworkAvailable(this)) {
            Toast.makeText(this, "인터넷 연결을 확인해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "대기 알림 재전송 중...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                NetworkMonitor.processPendingMessages(this@MainActivity)
            }
            updatePendingCount()
            Toast.makeText(this@MainActivity, "재전송 처리 완료", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, NotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun sendTestMessage(isDeposit: Boolean) {
        val token = etBotToken.text.toString().trim()
        val chatId = if (isDeposit) etDepositChatId.text.toString().trim()
        else etWithdrawalChatId.text.toString().trim()
        val typeLabel = if (isDeposit) "입금" else "출금"

        if (token.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "봇 토큰과 ${typeLabel} Chat ID를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "${typeLabel} 테스트 메시지 전송 중...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val sender = TelegramSender()
            val deviceLabel = settings.getDeviceLabel()
            val emoji = if (isDeposit) "\uD83D\uDCB0" else "\uD83D\uDCB8"
            val message = """
                <b>$emoji [$deviceLabel] [${typeLabel}] 테스트 메시지</b>

                은행 알림 전송 앱이 정상적으로 연결되었습니다.
                이 메시지가 보이면 ${typeLabel} 채팅방 설정이 올바릅니다.

                디바이스: $deviceLabel
                정산 시간: ${settings.settlementTime}
                데이터 초기화: 23:55
            """.trimIndent()

            val result = sender.sendMessage(token, chatId, message)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "${typeLabel} 테스트 전송 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity,
                        "전송 실패: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendTestNotification(type: String) {
        val token = settings.botToken
        val deviceNumber = settings.deviceNumber
        val deviceName = settings.deviceName
        val deviceLabel = settings.getDeviceLabel()

        val (notification, chatId) = when (type) {
            "deposit" -> {
                BankNotification(
                    bankName = "카카오뱅크",
                    amount = "50,000원",
                    senderName = "홍길동",
                    accountInfo = "3333-**-1234567",
                    originalText = "[카카오뱅크] 입금 50,000원 홍길동 잔액 1,234,567원",
                    packageName = "com.kakaobank.channel",
                    transactionType = TransactionType.DEPOSIT,
                    transactionStatus = TransactionStatus.NORMAL,
                    paymentMethod = "카카오페이"
                ) to settings.depositChatId
            }
            "withdrawal" -> {
                BankNotification(
                    bankName = "신한은행",
                    amount = "30,000원",
                    senderName = "배달의민족",
                    accountInfo = "110-***-123456",
                    originalText = "[신한은행] 출금 30,000원 네이버페이 배달의민족 잔액 970,000원",
                    packageName = "com.shinhan.sbanking",
                    transactionType = TransactionType.WITHDRAWAL,
                    transactionStatus = TransactionStatus.NORMAL,
                    paymentMethod = "네이버페이"
                ) to settings.withdrawalChatId
            }
            "deposit_failed" -> {
                BankNotification(
                    bankName = "KB국민은행",
                    amount = "100,000원",
                    senderName = "김철수",
                    accountInfo = "123-**-4567890",
                    originalText = "[KB국민은행] 이체입금 실패 100,000원 김철수 오류코드: E001",
                    packageName = "com.kbstar.kbbank",
                    transactionType = TransactionType.DEPOSIT,
                    transactionStatus = TransactionStatus.FAILED,
                    paymentMethod = "계좌이체"
                ) to settings.depositChatId
            }
            else -> {
                BankNotification(
                    bankName = "우리은행",
                    amount = "200,000원",
                    senderName = "이영희",
                    accountInfo = "1002-***-567890",
                    originalText = "[우리은행] 출금 실패 200,000원 카드결제 이영희 한도초과",
                    packageName = "com.wooribank.smart.npib",
                    transactionType = TransactionType.WITHDRAWAL,
                    transactionStatus = TransactionStatus.FAILED,
                    paymentMethod = "카드결제"
                ) to settings.withdrawalChatId
            }
        }

        if (token.isBlank() || chatId.isBlank()) {
            val label = if (type.contains("withdrawal")) "출금" else "입금"
            Toast.makeText(this, "봇 토큰과 ${label} Chat ID를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        val typeLabel = when (type) {
            "deposit" -> "입금"
            "withdrawal" -> "출금"
            "deposit_failed" -> "입금 실패"
            else -> "출금 실패"
        }
        Toast.makeText(this, "${typeLabel} 테스트 알림 전송 중...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val sender = TelegramSender()
            val message = sender.formatBankNotification(notification, deviceLabel, isTest = true)
            val (success, attempts) = sender.sendWithRetry(token, chatId, message)

            // 로그 DB에도 기록
            withContext(Dispatchers.IO) {
                logDb.insertLog(
                    notification, sentToTelegram = success,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = if (success) "성공" else "실패"
                )
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "${typeLabel} 테스트 전송 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "테스트 전송 실패 (${attempts}회 시도)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // === 내 계좌 관리 ===

    private val bankList = listOf(
        "선택 안 함",
        "KB국민은행", "신한은행", "하나은행", "우리은행", "NH농협은행",
        "카카오뱅크", "토스", "IBK기업은행", "SC제일은행", "씨티은행",
        "대구은행", "부산은행", "경남은행", "광주은행", "전북은행",
        "제주은행", "KDB산업은행", "수협은행", "우체국", "새마을금고", "신협",
        "카카오페이", "네이버페이", "페이코", "제로페이"
    )

    private fun showAddAccountDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(4))
        }

        val bankLabel = TextView(this).apply {
            text = "은행/앱"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(dpToPx(4), dpToPx(4), 0, dpToPx(2))
        }
        val spinnerBank = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, bankList)
        }
        val etName = EditText(this).apply {
            hint = "이름 (예: 김철수)"
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        val etNumber = EditText(this).apply {
            hint = "계좌번호 (예: 110-123-456789)"
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        val etMemo = EditText(this).apply {
            hint = "메모 (선택)"
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        layout.addView(bankLabel)
        layout.addView(spinnerBank)
        layout.addView(etName)
        layout.addView(etNumber)
        layout.addView(etMemo)

        AlertDialog.Builder(this)
            .setTitle("내 계좌 추가")
            .setView(layout)
            .setPositiveButton("추가") { _, _ ->
                val selectedBank = if (spinnerBank.selectedItemPosition == 0) "" else bankList[spinnerBank.selectedItemPosition]
                val name = etName.text.toString().trim()
                val number = etNumber.text.toString().trim()
                val memo = etMemo.text.toString().trim()
                if (name.isBlank() && number.isBlank() && selectedBank.isBlank()) {
                    Toast.makeText(this, "은행, 이름, 계좌번호 중 하나는 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val current = settings.myAccounts.toMutableList()
                current.add(MyAccountItem(accountNumber = number, accountName = name, bankName = selectedBank, memo = memo))
                settings.myAccounts = current
                // 계좌 추가 시 자동으로 내부거래 제외 토글 ON
                if (!settings.excludeInternalTransfers) {
                    settings.excludeInternalTransfers = true
                    switchExcludeInternal.isChecked = true
                }
                buildAccountViews()
                Toast.makeText(this, "추가되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditAccountDialog(index: Int, account: MyAccountItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(4))
        }

        val bankLabel = TextView(this).apply {
            text = "은행/앱"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(dpToPx(4), dpToPx(4), 0, dpToPx(2))
        }
        val spinnerBank = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, bankList)
        }
        val bankIndex = bankList.indexOf(account.bankName)
        spinnerBank.setSelection(if (bankIndex >= 0) bankIndex else 0)

        val etName = EditText(this).apply {
            hint = "이름 (예: 김철수)"
            setText(account.accountName)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        val etNumber = EditText(this).apply {
            hint = "계좌번호 (예: 110-123-456789)"
            setText(account.accountNumber)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        val etMemo = EditText(this).apply {
            hint = "메모 (선택)"
            setText(account.memo)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        layout.addView(bankLabel)
        layout.addView(spinnerBank)
        layout.addView(etName)
        layout.addView(etNumber)
        layout.addView(etMemo)

        AlertDialog.Builder(this)
            .setTitle("계좌 수정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val selectedBank = if (spinnerBank.selectedItemPosition == 0) "" else bankList[spinnerBank.selectedItemPosition]
                val name = etName.text.toString().trim()
                val number = etNumber.text.toString().trim()
                val memo = etMemo.text.toString().trim()
                if (name.isBlank() && number.isBlank() && selectedBank.isBlank()) {
                    Toast.makeText(this, "은행, 이름, 계좌번호 중 하나는 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val current = settings.myAccounts.toMutableList()
                current[index] = MyAccountItem(accountNumber = number, accountName = name, bankName = selectedBank, memo = memo)
                settings.myAccounts = current
                buildAccountViews()
                Toast.makeText(this, "수정되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("삭제") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("삭제 확인")
                    .setMessage("'${account.accountName}' 계좌를 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        val current = settings.myAccounts.toMutableList()
                        current.removeAt(index)
                        settings.myAccounts = current
                        buildAccountViews()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun buildAccountViews() {
        containerMyAccounts.removeAllViews()
        val accounts = settings.myAccounts
        if (accounts.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "등록된 계좌가 없습니다"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                gravity = Gravity.CENTER
            }
            containerMyAccounts.addView(emptyView)
            return
        }

        accounts.forEachIndexed { index, account ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(10), dpToPx(4), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    showEditAccountDialog(index, account)
                }
            }

            if (account.bankName.isNotBlank()) {
                val bankText = TextView(this).apply {
                    text = "\uD83C\uDFE6 ${account.bankName}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.parseColor("#1A237E"))
                    setTypeface(null, Typeface.BOLD)
                }
                infoLayout.addView(bankText)
            }

            if (account.accountName.isNotBlank()) {
                val nameText = TextView(this).apply {
                    val display = buildString {
                        append("\uD83D\uDC64 ${account.accountName}")
                        if (account.memo.isNotBlank()) append(" (${account.memo})")
                    }
                    text = display
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(Color.parseColor("#212121"))
                }
                infoLayout.addView(nameText)
            } else if (account.memo.isNotBlank()) {
                val memoText = TextView(this).apply {
                    text = "\uD83D\uDCDD ${account.memo}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(Color.parseColor("#616161"))
                }
                infoLayout.addView(memoText)
            }

            if (account.accountNumber.isNotBlank()) {
                val numberText = TextView(this).apply {
                    text = "\uD83D\uDCCB ${account.accountNumber}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#616161"))
                }
                infoLayout.addView(numberText)
            }

            val deleteBtn = TextView(this).apply {
                text = "\uD83D\uDDD1\uFE0F"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dpToPx(12), dpToPx(6), dpToPx(8), dpToPx(6))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("삭제 확인")
                        .setMessage("'${account.accountName}' 계좌를 삭제하시겠습니까?")
                        .setPositiveButton("삭제") { _, _ ->
                            val current = settings.myAccounts.toMutableList()
                            current.removeAt(index)
                            settings.myAccounts = current
                            buildAccountViews()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }

            row.addView(infoLayout)
            row.addView(deleteBtn)
            containerMyAccounts.addView(row)

            if (index < accounts.size - 1) {
                containerMyAccounts.addView(createDivider())
            }
        }
    }

    private fun showFilterDiagnostic() {
        val toggle = settings.excludeInternalTransfers
        val accounts = settings.myAccounts
        val rawJson = getSharedPreferences("bank_notify_settings", MODE_PRIVATE)
            .getString("my_accounts", "(없음)")

        val sb = StringBuilder()
        sb.appendLine("=== 내부거래 필터 진단 ===\n")
        sb.appendLine("토글 상태: ${if (toggle) "ON" else "OFF"}")
        sb.appendLine("등록 계좌 수: ${accounts.size}개\n")

        if (accounts.isEmpty()) {
            sb.appendLine("등록된 계좌가 없습니다!")
        } else {
            accounts.forEachIndexed { i, acc ->
                sb.appendLine("[${ i + 1}] 은행='${acc.bankName}' / 이름='${acc.accountName}' / 계좌='${acc.accountNumber}' / 메모='${acc.memo}'")
            }
        }

        // 시뮬레이션 테스트
        sb.appendLine("\n--- 시뮬레이션 테스트 ---")

        val testAcc = accounts.firstOrNull { it.accountName.isNotBlank() }
        val testName = testAcc?.accountName ?: "김철수"
        val testBank = testAcc?.bankName ?: "토스"
        val otherBank = if (testBank == "토스") "국민은행" else "토스"

        // 테스트1: 일반 알림 (정보 없음) + 은행 일치 → 차단
        val test1 = BankNotification(
            bankName = testBank, amount = null, senderName = null, accountInfo = null,
            originalText = "내 입출금통장에 돈이 입금됐어요",
            packageName = "viva.republica.toss",
            transactionType = TransactionType.DEPOSIT,
            transactionStatus = TransactionStatus.NORMAL, paymentMethod = "토스"
        )
        val r1 = SettingsManager.isInternalTransfer(test1, accounts)
        sb.appendLine("1) $testBank 일반알림 (정보없음) → ${if (r1) "차단 O" else "통과 X"}")

        // 테스트2: 같은 은행 + 같은 이름 → 차단
        val test2 = BankNotification(
            bankName = testBank, amount = "50,000원", senderName = testName, accountInfo = null,
            originalText = "$testBank 입금 50,000원 $testName",
            packageName = "viva.republica.toss",
            transactionType = TransactionType.DEPOSIT,
            transactionStatus = TransactionStatus.NORMAL, paymentMethod = "토스"
        )
        val r2 = SettingsManager.isInternalTransfer(test2, accounts)
        sb.appendLine("2) $testBank + '$testName' → ${if (r2) "차단 O" else "통과 (미등록)"}")

        // 테스트3: 같은 은행 + 다른 사람 → 통과
        val test3 = BankNotification(
            bankName = testBank, amount = "50,000원", senderName = "홍길동", accountInfo = null,
            originalText = "$testBank 입금 50,000원 홍길동",
            packageName = "viva.republica.toss",
            transactionType = TransactionType.DEPOSIT,
            transactionStatus = TransactionStatus.NORMAL, paymentMethod = "토스"
        )
        val r3 = SettingsManager.isInternalTransfer(test3, accounts)
        sb.appendLine("3) $testBank + '홍길동' → ${if (r3) "차단 X" else "통과 O"}")

        // 테스트4: 다른 은행 + 같은 이름 (동명이인) → 통과
        val test4 = BankNotification(
            bankName = otherBank, amount = "50,000원", senderName = testName, accountInfo = null,
            originalText = "$otherBank 입금 50,000원 $testName",
            packageName = "com.kbstar.kbbank",
            transactionType = TransactionType.DEPOSIT,
            transactionStatus = TransactionStatus.NORMAL, paymentMethod = "계좌이체"
        )
        val r4 = SettingsManager.isInternalTransfer(test4, accounts)
        sb.appendLine("4) $otherBank + '$testName' (동명이인) → ${if (r4) "차단 X" else "통과 O"}")

        if (!toggle) {
            sb.appendLine("\n결과: 토글이 꺼져있어 필터 미작동!")
        } else {
            sb.appendLine("\n* 정보 없는 알림 → 은행명으로 차단")
            sb.appendLine("* 정보 있는 알림 → 은행+이름으로 차단")
            sb.appendLine("* 계좌번호 등록 시 가장 정확")
        }

        sb.appendLine("\n--- 저장된 원본 JSON ---")
        sb.appendLine(rawJson)

        sb.appendLine("\n--- 최근 수신 알림 (패키지명) ---")
        sb.appendLine(NotificationListener.getRecentPackages(this))

        AlertDialog.Builder(this)
            .setTitle("내부거래 필터 진단")
            .setMessage(sb.toString())
            .setPositiveButton("확인", null)
            .show()
    }

    // === SMS 감지 관련 ===

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
            REQUEST_SMS_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                settings.smsEnabled = true
                switchSmsDetection.isChecked = true
                Toast.makeText(this, "SMS 권한이 허용되었습니다", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS 권한이 거부되었습니다", Toast.LENGTH_SHORT).show()
            }
            updateDetectionStatus()
        }
    }

    private fun updateDetectionStatus() {
        // SMS 상태
        val smsPermission = hasSmsPermission()
        val smsEnabled = settings.smsEnabled
        val btnSmsPermission = findViewById<Button>(R.id.btnRequestSmsPermission)

        if (smsEnabled && smsPermission) {
            tvSmsStatus.text = "SMS 감지: 작동중 \u2705"
            tvSmsStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            btnSmsPermission.visibility = View.GONE
        } else if (smsEnabled && !smsPermission) {
            tvSmsStatus.text = "SMS 감지: 권한 필요 \u26A0\uFE0F"
            tvSmsStatus.setTextColor(Color.parseColor("#F57C00"))
            btnSmsPermission.visibility = View.VISIBLE
        } else {
            tvSmsStatus.text = "SMS 감지: 꺼짐"
            tvSmsStatus.setTextColor(Color.parseColor("#9E9E9E"))
            btnSmsPermission.visibility = View.GONE
        }

        // 푸시 알림 상태
        val pushEnabled = settings.pushEnabled
        val serviceEnabled = isNotificationServiceEnabled()

        if (pushEnabled && serviceEnabled) {
            tvPushStatus.text = "알림 감지: 작동중 \u2705"
            tvPushStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else if (pushEnabled && !serviceEnabled) {
            tvPushStatus.text = "알림 감지: 서비스 비활성 \u26A0\uFE0F"
            tvPushStatus.setTextColor(Color.parseColor("#F57C00"))
        } else {
            tvPushStatus.text = "알림 감지: 꺼짐"
            tvPushStatus.setTextColor(Color.parseColor("#9E9E9E"))
        }
    }

    private fun sendTestSmsNotification(isDeposit: Boolean) {
        val token = settings.botToken
        val deviceNumber = settings.deviceNumber
        val deviceName = settings.deviceName
        val deviceLabel = settings.getDeviceLabel()

        val (notification, chatId) = if (isDeposit) {
            BankNotification(
                bankName = "KB국민은행",
                amount = "150,000원",
                senderName = "박영수",
                accountInfo = "123-**-4567890",
                originalText = "[KB국민] 입금 150,000원 박영수 잔액 2,345,678원",
                packageName = "sms:15881688",
                transactionType = TransactionType.DEPOSIT,
                transactionStatus = TransactionStatus.NORMAL,
                paymentMethod = "계좌이체",
                source = "SMS"
            ) to settings.depositChatId
        } else {
            BankNotification(
                bankName = "하나은행",
                amount = "25,000원",
                senderName = "쿠팡",
                accountInfo = "910-**-123456",
                originalText = "[하나] 출금 25,000원 카드결제 쿠팡 잔액 875,000원",
                packageName = "sms:15881111",
                transactionType = TransactionType.WITHDRAWAL,
                transactionStatus = TransactionStatus.NORMAL,
                paymentMethod = "카드결제",
                source = "SMS"
            ) to settings.withdrawalChatId
        }

        if (token.isBlank() || chatId.isBlank()) {
            val label = if (isDeposit) "입금" else "출금"
            Toast.makeText(this, "봇 토큰과 ${label} Chat ID를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        val typeLabel = if (isDeposit) "입금" else "출금"
        Toast.makeText(this, "SMS ${typeLabel} 테스트 전송 중...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val sender = TelegramSender()
            val message = sender.formatBankNotification(notification, deviceLabel, isTest = true)
            val (success, attempts) = sender.sendWithRetry(token, chatId, message)

            withContext(Dispatchers.IO) {
                logDb.insertLog(
                    notification, sentToTelegram = success,
                    deviceNumber = deviceNumber, deviceName = deviceName,
                    telegramStatus = if (success) "성공" else "실패",
                    source = "SMS"
                )
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "SMS ${typeLabel} 테스트 전송 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "테스트 전송 실패 (${attempts}회 시도)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val REQUEST_SMS_PERMISSION = 1001
    }
}
