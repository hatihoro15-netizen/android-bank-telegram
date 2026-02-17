package com.banknotify.telegram

object DuplicateDetector {

    private const val DEDUP_WINDOW_MS = 30_000L // 30초

    private data class TransactionRecord(
        val timestamp: Long,
        val sources: MutableSet<String>
    )

    private val recentTransactions = mutableMapOf<String, TransactionRecord>()

    /**
     * 중복 여부 체크.
     * @return true이면 이미 동일 거래가 처리됨 (중복)
     */
    /**
     * 중복 여부 체크.
     * 같은 소스(packageName)에서 온 동일 금액+이름은 중복 허용 (동시 입금 가능)
     * 다른 소스에서 온 동일 금액+이름만 중복 차단 (카카오톡+은행앱 이중 알림)
     */
    @Synchronized
    fun isDuplicate(amount: String?, senderName: String?, source: String): Boolean {
        cleanOldEntries()
        val key = "${amount.orEmpty()}|${senderName.orEmpty()}"
        if (key == "|") return false

        val existing = recentTransactions[key]
        if (existing != null && (System.currentTimeMillis() - existing.timestamp) < DEDUP_WINDOW_MS) {
            // 같은 소스에서 온 건 중복 아님 (동시 입금 허용)
            if (existing.sources.contains(source)) return false
            existing.sources.add(source)
            return true
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
    }
}
