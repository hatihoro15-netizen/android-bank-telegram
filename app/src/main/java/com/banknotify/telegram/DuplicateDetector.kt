package com.banknotify.telegram

object DuplicateDetector {

    private const val DEDUP_WINDOW_MS = 30_000L // 30초
    private const val CROSS_APP_WINDOW_MS = 10_000L // 10초

    private data class RecentTransaction(
        val timestamp: Long,
        val amount: String,
        val senderName: String?,
        val source: String,
        var matched: Boolean = false
    )

    private val recentList = mutableListOf<RecentTransaction>()

    /**
     * 중복 여부 체크.
     *
     * 1. 다른 소스에서 온 동일 금액+이름 → 중복
     * 2. 다른 소스, 같은 금액, 이름 유사 (김*동 ↔ 김서동) → 중복
     * 3. 그 외 → 중복 아님 (다른 사람 보호)
     */
    @Synchronized
    fun isDuplicate(amount: String?, senderName: String?, source: String): Boolean {
        cleanOldEntries()

        if (amount.isNullOrBlank()) return false
        val now = System.currentTimeMillis()

        // 체크 1: 정확히 같은 금액+이름, 다른 소스
        val exactMatch = recentList.find { record ->
            !record.matched &&
            record.amount == amount &&
            record.senderName == senderName &&
            record.source != source &&
            (now - record.timestamp) < DEDUP_WINDOW_MS
        }
        if (exactMatch != null) {
            exactMatch.matched = true
            return true
        }

        // 체크 2: 같은 금액, 이름 유사 (마스킹 패턴), 다른 소스
        val similarNameMatch = recentList.find { record ->
            !record.matched &&
            record.amount == amount &&
            record.source != source &&
            record.senderName != senderName &&
            (now - record.timestamp) < CROSS_APP_WINDOW_MS &&
            areNamesSimilar(record.senderName, senderName)
        }
        if (similarNameMatch != null) {
            similarNameMatch.matched = true
            return true
        }

        // 새 거래 기록
        recentList.add(RecentTransaction(
            timestamp = now,
            amount = amount,
            senderName = senderName,
            source = source
        ))
        return false
    }

    /**
     * 이름 유사도 체크 (한국어 마스킹 패턴).
     * - 김*동 ↔ 김서동 → 첫글자+끝글자 일치 → 유사
     * - 이*수 ↔ 이민수 → 유사
     * - 김*동 ↔ 박*수 → 첫글자 불일치 → 다름
     */
    private fun areNamesSimilar(name1: String?, name2: String?): Boolean {
        if (name1.isNullOrBlank() || name2.isNullOrBlank()) return false
        if (name1 == name2) return true

        val n1 = name1.trim()
        val n2 = name2.trim()

        // 한쪽이 다른 쪽을 포함하면 유사
        if (n1.contains(n2) || n2.contains(n1)) return true

        // 마스킹된 이름과 원본 비교 (X*Y 또는 X○Y 패턴)
        val (masked, full) = when {
            n1.contains("*") || n1.contains("\u25CB") -> n1 to n2
            n2.contains("*") || n2.contains("\u25CB") -> n2 to n1
            else -> return false
        }

        if (masked.length < 2 || full.length < 2) return false

        return masked.first() == full.first() && masked.last() == full.last()
    }

    @Synchronized
    fun getSourcesForTransaction(amount: String?, senderName: String?): Set<String> {
        return recentList
            .filter { it.amount == amount.orEmpty() && it.senderName == senderName }
            .map { it.source }
            .toSet()
    }

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS * 2
        recentList.removeAll { it.timestamp < cutoff }
    }
}
