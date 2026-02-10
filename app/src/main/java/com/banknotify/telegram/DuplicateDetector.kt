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
    @Synchronized
    fun isDuplicate(amount: String?, senderName: String?, source: String): Boolean {
        cleanOldEntries()
        val key = "${amount.orEmpty()}|${senderName.orEmpty()}"
        if (key == "|") return false

        val existing = recentTransactions[key]
        if (existing != null && (System.currentTimeMillis() - existing.timestamp) < DEDUP_WINDOW_MS) {
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
