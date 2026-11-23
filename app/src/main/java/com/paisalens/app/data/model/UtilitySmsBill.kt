package com.paisalens.app.data.model

enum class UtilityBillKind(val label: String) {
    ELECTRICITY("Electricity"),
    BROADBAND("Broadband"),
    MOBILE_POSTPAID("Mobile postpaid"),
}

/** One billing cycle. Only masked display details and hashed SMS identifiers are retained. */
data class UtilitySmsBill(
    val id: Long = 0,
    val kind: UtilityBillKind,
    val providerKey: String,
    val providerName: String,
    val accountKey: String? = null,
    val accountLastFour: String? = null,
    val referenceKey: String? = null,
    val billingPeriod: String? = null,
    val amountMinor: Long,
    val dueDateEpochDay: Long,
    val detectedAt: Long,
    val noticeKeys: Set<String>,
    val earlierDueDates: Set<Long> = emptySet(),
    val trackedBillId: Long? = null,
    val dismissed: Boolean = false,
) {
    val title: String
        get() = buildString {
            append(providerName)
            append(" · ")
            append(kind.label)
            accountLastFour?.let { append(" · ••$it") }
        }
}

/**
 * A bill number is sufficient to recognize a cycle. Otherwise require the same service account
 * and the same stated billing period or due date. A conflicting account, bill number, or period
 * always wins over a matching due date.
 */
internal fun findUtilitySmsBillMatch(
    notice: UtilitySmsBill,
    candidates: List<UtilitySmsBill>,
): UtilitySmsBill? {
    val compatible = candidates.filter { bill ->
        bill.providerKey == notice.providerKey && bill.kind == notice.kind &&
            compatibleBillKeys(bill.accountKey, notice.accountKey) &&
            compatibleBillKeys(bill.referenceKey, notice.referenceKey) &&
            compatibleBillKeys(bill.billingPeriod, notice.billingPeriod)
    }
    val referenceMatches = notice.referenceKey?.let { key -> compatible.filter { it.referenceKey == key } }.orEmpty()
    if (referenceMatches.isNotEmpty()) return referenceMatches.singleOrNull()
    val account = notice.accountKey ?: return null
    return compatible.singleOrNull { bill ->
        bill.accountKey == account && (
            (notice.billingPeriod != null && notice.billingPeriod == bill.billingPeriod) ||
                bill.dueDateEpochDay == notice.dueDateEpochDay ||
                notice.dueDateEpochDay in bill.earlierDueDates
            )
    }
}

private fun compatibleBillKeys(old: String?, incoming: String?): Boolean =
    old == null || incoming == null || old == incoming

internal fun UtilitySmsBill.withNotice(notice: UtilitySmsBill): UtilitySmsBill {
    val isNewer = notice.detectedAt > detectedAt
    return copy(
        accountKey = accountKey ?: notice.accountKey,
        accountLastFour = accountLastFour ?: notice.accountLastFour,
        referenceKey = referenceKey ?: notice.referenceKey,
        billingPeriod = billingPeriod ?: notice.billingPeriod,
        amountMinor = if (isNewer) notice.amountMinor else amountMinor,
        dueDateEpochDay = if (isNewer) notice.dueDateEpochDay else dueDateEpochDay,
        detectedAt = maxOf(detectedAt, notice.detectedAt),
        noticeKeys = noticeKeys + notice.noticeKeys,
        earlierDueDates = (earlierDueDates + dueDateEpochDay + notice.dueDateEpochDay) -
            (if (isNewer) notice.dueDateEpochDay else dueDateEpochDay),
    )
}
