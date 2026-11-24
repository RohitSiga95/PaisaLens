package com.paisalens.app.sms

internal enum class SmsSyncMode { QUICK, FULL }

internal data class SmsInboxMarker(val rowId: Long, val fingerprint: String)

/**
 * Device-local only. Last inbox rows are checked by content as well as ID because
 * the provider can reuse row IDs. Incomplete rows are retried even outside the overlap.
 */
internal data class SmsInboxCheckpoint(
    val recentRows: List<SmsInboxMarker>,
    val incompleteRowIds: Set<Long>,
) {
    val anchor: SmsInboxMarker get() = recentRows.last()

    fun encode(): String = listOf(
        FORMAT,
        recentRows.joinToString(",") { "${it.rowId}:${it.fingerprint}" },
        incompleteRowIds.sorted().joinToString(","),
    ).joinToString("|")

    companion object {
        const val OVERLAP_ROWS = 32
        const val MAX_INCOMPLETE_ROWS = 200
        private const val FORMAT = "1"
        private val fingerprintPattern = Regex("[0-9a-f]{64}")

        fun decode(value: String?): SmsInboxCheckpoint? {
            if (value == null || value.length > 10_000) return null
            val sections = value.split('|')
            if (sections.size != 3 || sections[0] != FORMAT) return null
            val markers = sections[1].split(',').map { item ->
                val fields = item.split(':', limit = 2)
                val id = fields.first().toLongOrNull()?.takeIf { it > 0L } ?: return null
                val fingerprint = fields.getOrNull(1)?.takeIf(fingerprintPattern::matches) ?: return null
                SmsInboxMarker(id, fingerprint)
            }
            if (markers.size !in 1..OVERLAP_ROWS || markers.zipWithNext().any { (a, b) -> a.rowId >= b.rowId }) return null
            val incompleteIds = sections[2].takeIf(String::isNotEmpty)?.split(',')?.map { item ->
                item.toLongOrNull()?.takeIf { it in 1..markers.last().rowId } ?: return null
            }?.toSet().orEmpty()
            if (incompleteIds.size > MAX_INCOMPLETE_ROWS) return null
            return SmsInboxCheckpoint(markers, incompleteIds)
        }
    }
}
