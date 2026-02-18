package com.banknotify.telegram

object DuplicateDetector {

    private const val DEDUP_WINDOW_MS = 30_000L // 30초
    private const val CROSS_APP_WINDOW_MS = 10_000L // 10초: 다른 앱에서 같은 금액 → 이중 알림

    private data class TransactionRecord(
        val timestamp: Long,
        val sources: MutableSet<String>
    )

    private val recentTransactions = mutableMapOf<String, TransactionRecord>()
    private val recentAmounts = mutableMapOf<String, TransactionRecord>()

    /**
     * 중복 여부 체크.
     * 1. 같은 소스(packageName)에서 온 동일 금액+이름 → 중복 아님 (동시 입금 가능)
     * 2. 다른 소스에서 온 동일 금액+이름 → 중복 (카카오톡+은행앱 이중 알림)
     * 3. 다른 소스에서 온 동일 금액 (이름 다름) 10초 이내 → 중복 (실명+닉네임 이중 알림)
     */
    @Synchronized
    fun isDuplicate(amount: String?, senderName: String?, source: String): Boolean {
        cleanOldEntries()
        val key = "${amount.orEmpty()}|${senderName.orEmpty()}"
        if (key == "|") return false

        // 체크 1: 금액+이름 기반 (기존 로직)
        val existing = recentTransactions[key]
        if (existing != null && (System.currentTimeMillis() - existing.timestamp) < DEDUP_WINDOW_MS) {
            if (existing.sources.contains(source)) return false
            existing.sources.add(source)
            return true
        }

        // 체크 2: 금액만 기반 크로스앱 중복 (실명/닉네임 다른 경우)
        val amountKey = amount.orEmpty()
        if (amountKey.isNotBlank()) {
            val amountRecord = recentAmounts[amountKey]
            if (amountRecord != null &&
                (System.currentTimeMillis() - amountRecord.timestamp) < CROSS_APP_WINDOW_MS &&
                !amountRecord.sources.contains(source)) {
                // 다른 앱에서 같은 금액이 10초 이내 → 이중 알림
                amountRecord.sources.add(source)
                return true
            }
            if (amountRecord == null || (System.currentTimeMillis() - amountRecord.timestamp) >= CROSS_APP_WINDOW_MS) {
                recentAmounts[amountKey] = TransactionRecord(
                    timestamp = System.currentTimeMillis(),
                    sources = mutableSetOf(source)
                )
            } else {
                amountRecord.sources.add(source)
            }
        }

        recentTransactions[key] = TransactionRecord(
            timestamp = System.currentTimeMillis(),
            sources = mutableSetOf(source)
        )
        return false
    }

    @Synchronized
    fun getSourcesForTransaction(amount: String?, senderName: String?): Set<String> {
        val key = "${amount.orEmpty()}|${senderName.orEmpty()}"
        return recentTransactions[key]?.sources?.toSet() ?: emptySet()
    }

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS * 2
        recentTransactions.entries.removeAll { it.value.timestamp < cutoff }
        recentAmounts.entries.removeAll { it.value.timestamp < cutoff }
    }
}
