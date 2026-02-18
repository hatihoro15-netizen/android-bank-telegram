package com.banknotify.telegram

object DuplicateDetector {

    private const val DEDUP_WINDOW_MS = 30_000L // 30초
    private const val CROSS_APP_WINDOW_MS = 10_000L // 10초: 크로스앱 이중 알림

    // 카카오 생태계: 카카오뱅크 알림 + 카카오톡 알림이 동시에 올 수 있음
    private val knownEcosystemPairs = listOf(
        setOf("com.kakaobank.channel", "com.kakao.talk"),
        setOf("com.kakaopay.app", "com.kakao.talk")
    )

    private data class RecentTransaction(
        val timestamp: Long,
        val amount: String,
        val senderName: String?,
        val source: String,
        var matched: Boolean = false // 이미 크로스앱 중복으로 매칭됨
    )

    private val recentList = mutableListOf<RecentTransaction>()

    /**
     * 중복 여부 체크 (스마트 버전).
     *
     * 1. 같은 소스에서 온 동일 금액+이름 → 중복 아님 (동시 입금 가능)
     * 2. 다른 소스에서 온 동일 금액+이름 → 중복 (이중 알림)
     * 3. 다른 소스, 같은 금액, 이름 유사 (마스킹 패턴) → 중복 (김*동 ↔ 김서동)
     * 4. 카카오 생태계 페어, 같은 금액 10초 이내 → 중복 (닉네임 케이스: 김*우 ↔ ㅊ○)
     * 5. 그 외 다른 소스, 같은 금액, 이름 다름 → 중복 아님 (다른 사람)
     */
    @Synchronized
    fun isDuplicate(amount: String?, senderName: String?, source: String): Boolean {
        cleanOldEntries()

        if (amount.isNullOrBlank()) return false
        val now = System.currentTimeMillis()

        // 체크 1+2: 정확히 같은 금액+이름
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

        // 체크 3: 같은 금액, 이름이 유사 (마스킹 패턴 매칭)
        val similarNameMatch = recentList.find { record ->
            !record.matched &&
            record.amount == amount &&
            record.source != source &&
            record.senderName != senderName && // 이름이 다르지만
            (now - record.timestamp) < CROSS_APP_WINDOW_MS &&
            areNamesSimilar(record.senderName, senderName) // 유사함
        }
        if (similarNameMatch != null) {
            similarNameMatch.matched = true
            return true
        }

        // 체크 4: 카카오 생태계 페어 (닉네임 케이스 - 이름 매칭 불가능)
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
            else -> return false // 둘 다 마스킹 아니면 비교 불가
        }

        if (masked.length < 2 || full.length < 2) return false

        // 첫글자 + 끝글자 비교
        return masked.first() == full.first() && masked.last() == full.last()
    }

    /**
     * 알려진 생태계 페어인지 체크 (예: 카카오뱅크 + 카카오톡).
     */
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
