package com.banknotify.telegram

object DuplicateDetector {

    private const val DEDUP_WINDOW_MS = 30_000L // 30초
    private const val CROSS_APP_WINDOW_MS = 10_000L // 10초

    private val knownEcosystemPairs = listOf(
        setOf("com.kakaobank.channel", "com.kakao.talk"),
        setOf("com.kakaopay.app", "com.kakao.talk")
    )

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
     * 1. 다른 소스, 동일 금액+이름 → 중복
     * 2. 다른 소스, 같은 금액, 이름 유사 (김*동 ↔ 김서동) → 중복
     * 3. 카카오 생태계 페어, 같은 금액 10초 이내 → 중복 (닉네임 케이스)
     * 4. 그 외 → 중복 아님
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

        // 체크 3: 카카오 생태계 페어 (닉네임 케이스)
        val ecosystemMatch = recentList.find { record ->
            !record.matched &&
            record.amount == amount &&
            record.source != source &&
            (now - record.timestamp) < CROSS_APP_WINDOW_MS &&
            isKnownEcosystemPair(record.source, source)
        }
        if (ecosystemMatch != null) {
            ecosystemMatch.matched = true
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

    private fun areNamesSimilar(name1: String?, name2: String?): Boolean {
        if (name1.isNullOrBlank() || name2.isNullOrBlank()) return false
        if (name1 == name2) return true

        val n1 = name1.trim()
        val n2 = name2.trim()

        if (n1.contains(n2) || n2.contains(n1)) return true

        val (masked, full) = when {
            n1.contains("*") || n1.contains("\u25CB") -> n1 to n2
            n2.contains("*") || n2.contains("\u25CB") -> n2 to n1
            else -> return false
        }

        if (masked.length < 2 || full.length < 2) return false
        return masked.first() == full.first() && masked.last() == full.last()
    }

    private fun isKnownEcosystemPair(source1: String, source2: String): Boolean {
        return knownEcosystemPairs.any { pair ->
            pair.contains(source1) && pair.contains(source2)
        }
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
