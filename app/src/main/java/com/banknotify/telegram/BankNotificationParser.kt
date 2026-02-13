package com.banknotify.telegram

enum class TransactionType(val label: String, val emoji: String) {
    DEPOSIT("입금", "\uD83D\uDCB0"),
    WITHDRAWAL("출금", "\uD83D\uDCB8"),
    UNKNOWN("알수없음", "\uD83D\uDCCB")
}

enum class TransactionStatus(val label: String, val emoji: String) {
    NORMAL("정상", ""),
    FAILED("실패", "\u274C"),
    CANCELLED("취소", "\uD83D\uDEAB"),
    IGNORED("무시", ""),
    INTERNAL("내부거래", "\uD83D\uDD04")
}

data class BankNotification(
    val bankName: String,
    val amount: String?,
    val senderName: String?,
    val accountInfo: String?,
    val originalText: String,
    val packageName: String,
    val transactionType: TransactionType,
    val transactionStatus: TransactionStatus = TransactionStatus.NORMAL,
    val paymentMethod: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "알림"
)

class BankNotificationParser {

    private val monitoredPackages = mapOf(
        "com.kbstar.kbbank" to "KB국민은행",
        "com.kbstar.reboot" to "KB국민은행",
        "com.shinhan.sbanking" to "신한은행",
        "com.shinhan.sbanking.mall" to "신한은행",
        "com.kebhana.hanapush" to "하나은행",
        "com.hanaskcard.rocomo.potal" to "하나은행",
        "com.wooribank.smart.npib" to "우리은행",
        "com.woori.smartbanking" to "우리은행",
        "nh.smart.banking" to "NH농협은행",
        "com.nh.cashcook" to "NH농협은행",
        "com.kakaobank.channel" to "카카오뱅크",
        "com.ibk.neobanking" to "IBK기업은행",
        "com.epost.psf.sdsi" to "우체국",
        "com.kfcc.member" to "새마을금고",
        "com.smg.spbs" to "새마을금고",
        "com.cu.sb" to "신협",
        "com.scbank.ma30" to "SC제일은행",
        "com.citibank.citimobile" to "씨티은행",
        "com.dgb.mobilebranch" to "대구은행",
        "com.bnk.bsb" to "부산은행",
        "com.bnk.kn" to "경남은행",
        "com.knb.psb" to "광주은행",
        "com.jeonbukbank.jbmb" to "전북은행",
        "com.jeju.jejubank" to "제주은행",
        "com.kdb.touch" to "KDB산업은행",
        "com.suhyup.mbanking" to "수협은행",
        "com.kakaopay.app" to "카카오페이",
        "viva.republica.toss" to "토스",
        "com.naverfin.payapp" to "네이버페이",
        "com.nhn.android.search" to "네이버",
        "com.nhnent.payapp" to "페이코",
        "com.payco.app" to "페이코",
        "com.kftc.zeropay.consumer" to "제로페이",
        "kr.or.zeropay.zip" to "제로페이"
    )

    private val depositKeywords = listOf(
        "받기완료", "받기 완료", "송금받기", "이체입금", "입금완료", "입금알림",
        "받아주세요", "보냈어요", "보냈습니다",
        "입금", "충전", "받았"
    )

    private val withdrawalKeywords = listOf(
        "보내기완료", "보내기 완료", "출금완료", "이체완료", "카드승인",
        "출금", "이체", "결제", "송금", "차감", "인출"
    )

    private val failedKeywords = listOf(
        "실패", "불가", "오류", "에러", "거부", "거절", "반려", "미승인"
    )

    private val cancelledKeywords = listOf(
        "취소", "환불", "철회", "복원", "반품"
    )

    private val excludeKeywords = listOf(
        "광고", "이벤트", "혜택", "할인", "대출", "보험", "카드발급",
        "인증번호", "OTP", "업데이트", "설치", "다운로드", "프로모션",
        "캠페인", "공지", "가입", "추천", "무료", "당첨", "경품",
        "마케팅", "수신동의", "약관"
    )

    private val methodKeywords = mapOf(
        "카카오페이" to listOf("카카오페이", "카카오머니", "카카오송금"),
        "네이버페이" to listOf("네이버페이", "N페이", "네이버 페이"),
        "제로페이" to listOf("제로페이"),
        "페이코" to listOf("페이코", "PAYCO"),
        "토스" to listOf("토스머니", "토스송금", "토스입금", "토스"),
        "연락처송금" to listOf("연락처송금", "연락처 송금", "연락처"),
        "체크/카드" to listOf("체크카드", "카드결제", "카드승인", "신용카드"),
        "카드결제" to listOf("체크카드", "카드결제", "카드승인", "신용카드")
    )

    private val packagePaymentMethod = mapOf(
        "com.kakaopay.app" to "카카오페이",
        "viva.republica.toss" to "토스",
        "com.naverfin.payapp" to "네이버페이",
        "com.nhnent.payapp" to "페이코",
        "com.payco.app" to "페이코",
        "com.kftc.zeropay.consumer" to "제로페이",
        "kr.or.zeropay.zip" to "제로페이"
    )

    // 가맹점/결제단말 앱: "결제완료"가 입금(매출)을 의미하는 패키지
    private val merchantPackages = setOf(
        "com.kftc.zeropay.consumer",
        "kr.or.zeropay.zip"
    )

    private val amountPattern = Regex("""[\d,]+\s*원""")
    private val accountPattern = Regex("""\d{2,6}[-*]\d{2,8}[-*]?\d{0,6}""")

    fun isMonitoredApp(packageName: String): Boolean = packageName in monitoredPackages

    fun getBankName(packageName: String): String = monitoredPackages[packageName] ?: packageName

    fun isTransactionNotification(title: String?, text: String?): Boolean {
        val combined = "${title.orEmpty()} ${text.orEmpty()}"
        if (excludeKeywords.any { combined.contains(it) }) return false
        val hasTransactionKw = depositKeywords.any { combined.contains(it) } ||
                withdrawalKeywords.any { combined.contains(it) }
        return hasTransactionKw
    }

    fun getIgnoreReason(title: String?, text: String?, packageName: String = ""): String? {
        val combined = "${title.orEmpty()} ${text.orEmpty()}"
        val matched = excludeKeywords.firstOrNull { combined.contains(it) }
        if (matched != null) return "제외 키워드: $matched"
        val isMerchant = packageName in merchantPackages
        val hasMerchantKeyword = isMerchant && combined.contains("결제완료")
        if (!depositKeywords.any { combined.contains(it) } &&
            !withdrawalKeywords.any { combined.contains(it) } &&
            !hasMerchantKeyword) {
            return "거래 키워드 없음"
        }
        return null
    }

    fun detectTransactionType(title: String?, text: String?, packageName: String = ""): TransactionType {
        val combined = "${title.orEmpty()} ${text.orEmpty()}"
        // 가맹점 앱: "결제완료" → 입금(매출), "환불" → 출금
        if (packageName in merchantPackages) {
            if (combined.contains("결제완료")) return TransactionType.DEPOSIT
            if (combined.contains("환불")) return TransactionType.WITHDRAWAL
        }
        if (depositKeywords.any { combined.contains(it) }) return TransactionType.DEPOSIT
        if (withdrawalKeywords.any { combined.contains(it) }) return TransactionType.WITHDRAWAL
        return TransactionType.UNKNOWN
    }

    fun detectTransactionStatus(title: String?, text: String?): TransactionStatus {
        val combined = "${title.orEmpty()} ${text.orEmpty()}"
        if (cancelledKeywords.any { combined.contains(it) }) return TransactionStatus.CANCELLED
        if (failedKeywords.any { combined.contains(it) }) return TransactionStatus.FAILED
        return TransactionStatus.NORMAL
    }

    fun detectPaymentMethod(
        packageName: String,
        text: String,
        enabledMethods: List<String>,
        defaultFallback: String
    ): String {
        val specificMethods = enabledMethods.filter {
            it != "계좌이체" && it != "계좌출금" && it != "기타"
        }
        for (method in specificMethods) {
            val keywords = methodKeywords[method] ?: listOf(method)
            if (keywords.any { text.contains(it, ignoreCase = true) }) return method
        }
        packagePaymentMethod[packageName]?.let { pkgMethod ->
            if (pkgMethod in enabledMethods) return pkgMethod
        }
        if (defaultFallback in enabledMethods) return defaultFallback
        if ("기타" in enabledMethods) return "기타"
        return enabledMethods.firstOrNull() ?: "알수없음"
    }

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        enabledDepositMethods: List<String>,
        enabledWithdrawalMethods: List<String>
    ): BankNotification {
        val bankName = monitoredPackages[packageName] ?: packageName
        val combined = "${title.orEmpty()} ${text.orEmpty()}"
        val amount = amountPattern.find(combined)?.value
        val accountInfo = accountPattern.find(combined)?.value
        val senderName = extractSenderName(combined, amount)
        val transactionType = detectTransactionType(title, text, packageName)
        val transactionStatus = detectTransactionStatus(title, text)

        val (methods, fallback) = when (transactionType) {
            TransactionType.DEPOSIT -> enabledDepositMethods to "계좌이체"
            TransactionType.WITHDRAWAL -> enabledWithdrawalMethods to "계좌출금"
            TransactionType.UNKNOWN -> enabledDepositMethods to "기타"
        }
        val paymentMethod = detectPaymentMethod(packageName, combined, methods, fallback)

        return BankNotification(
            bankName = bankName,
            amount = amount,
            senderName = senderName,
            accountInfo = accountInfo,
            originalText = combined.trim(),
            packageName = packageName,
            transactionType = transactionType,
            transactionStatus = transactionStatus,
            paymentMethod = paymentMethod
        )
    }

    private fun extractSenderName(text: String, amount: String?): String? {
        if (amount != null) {
            val afterAmount = text.substringAfter(amount).trim()
            val nameMatch = Regex("""^[\s]*([가-힣]{2,5})""").find(afterAmount)
            if (nameMatch != null) return nameMatch.groupValues[1]
        }
        val fromPattern = Regex("""([가-힣]{2,5})님""").find(text)
        if (fromPattern != null) return fromPattern.groupValues[1]
        val labelPattern = Regex("""(?:보낸\s*분|입금자|보내신\s*분)\s*[:\s]\s*([가-힣]{2,5})""").find(text)
        if (labelPattern != null) return labelPattern.groupValues[1]
        return null
    }
}
