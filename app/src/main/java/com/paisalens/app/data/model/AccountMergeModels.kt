package com.paisalens.app.data.model

import com.paisalens.app.sms.BankSmsSupport
import java.util.Locale

enum class AccountMergeError {
    NAME_REQUIRED,
    AT_LEAST_TWO_REQUIRED,
    ACCOUNT_NOT_FOUND,
    ALREADY_MERGED,
    UNSUPPORTED_ACCOUNT_TYPE,
    MIXED_ACCOUNT_TYPES,
    HAS_RECONCILIATIONS,
}

sealed interface AccountMergeResult {
    data class Success(
        val canonicalAccountId: Long,
        val mergedAccountIds: Set<Long>,
        val mergedName: String,
        val accountType: AccountType,
        val memberCount: Int,
    ) : AccountMergeResult

    data class Failure(
        val error: AccountMergeError,
        val message: String,
        val accountIds: Set<Long> = emptySet(),
    ) : AccountMergeResult
}

/** Validates only user-visible merge rules; persistence performs the same check atomically. */
fun validateAccountMergeSelection(
    accounts: List<AccountProfile>,
    selectedIds: Collection<Long>,
    mergedName: String,
): AccountMergeResult.Failure? {
    val cleanName = normalizedAccountMergeName(mergedName)
    if (cleanName.isBlank()) {
        return AccountMergeResult.Failure(
            error = AccountMergeError.NAME_REQUIRED,
            message = "Enter a name for the merged account",
        )
    }

    val distinctIds = selectedIds.filter { it > 0 }.toCollection(linkedSetOf())
    if (distinctIds.size < 2) {
        return AccountMergeResult.Failure(
            error = AccountMergeError.AT_LEAST_TWO_REQUIRED,
            message = "Choose at least two different accounts",
            accountIds = distinctIds,
        )
    }

    val accountsById = accounts.associateBy(AccountProfile::id)
    val missingIds = distinctIds.filterTo(linkedSetOf()) { it !in accountsById }
    if (missingIds.isNotEmpty()) {
        return AccountMergeResult.Failure(
            error = AccountMergeError.ACCOUNT_NOT_FOUND,
            message = if (missingIds.size == 1) {
                "The selected account no longer exists"
            } else {
                "Some selected accounts no longer exist"
            },
            accountIds = missingIds,
        )
    }

    val selected = distinctIds.map(accountsById::getValue)
    val logicalRootIds = selected.mapTo(linkedSetOf()) {
        canonicalAccountId(accountsById, it.id)
    }
    if (logicalRootIds.size < 2) {
        return AccountMergeResult.Failure(
            error = AccountMergeError.ALREADY_MERGED,
            message = "These accounts are already merged together",
            accountIds = distinctIds,
        )
    }

    val accountTypes = selected.mapTo(linkedSetOf(), AccountProfile::type)
    if (accountTypes.size != 1) {
        return AccountMergeResult.Failure(
            error = AccountMergeError.MIXED_ACCOUNT_TYPES,
            message = "Bank accounts and credit cards cannot be merged together",
            accountIds = distinctIds,
        )
    }

    if (accountTypes.single() !in MERGEABLE_ACCOUNT_TYPES) {
        return AccountMergeResult.Failure(
            error = AccountMergeError.UNSUPPORTED_ACCOUNT_TYPE,
            message = "Only bank accounts or credit cards can be merged",
            accountIds = distinctIds,
        )
    }
    return null
}

fun normalizedAccountMergeName(value: String): String = value
    .trim()
    .replace(Regex("\\s+"), " ")
    .take(48)

/** Resolves restored legacy chains defensively; new merges are always stored one level deep. */
fun canonicalAccountId(accountsById: Map<Long, AccountProfile>, accountId: Long): Long {
    var current = accountId
    val visited = linkedSetOf<Long>()
    while (visited.add(current)) {
        val parent = accountsById[current]?.mergedIntoAccountId ?: return current
        if (parent !in accountsById) return current
        current = parent
    }
    // A malformed backup must not make identity resolution loop forever. Choosing the smallest
    // participating ID preserves the same deterministic-root rule used by a normal merge.
    return visited.minOrNull() ?: accountId
}

internal data class BankAccountIdentity(val institutionKey: String, val lastFour: String)

internal fun recognizedAccountBank(account: AccountProfile): String? =
    BankSmsSupport.bankKey(account.institution.orEmpty()) ?:
        BankSmsSupport.bankKey(account.availabilitySender.orEmpty()) ?:
        BankSmsSupport.bankKey(account.name).takeIf { account.institution.isNullOrBlank() }

internal fun bankAccountIdentity(account: AccountProfile): BankAccountIdentity? {
    if (account.type != AccountType.BANK_ACCOUNT) return null
    val lastFour = account.accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }
        ?: return null
    val bank = recognizedAccountBank(account) ?: account.institution
        ?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.takeIf(String::isNotBlank)
        ?: return null
    return BankAccountIdentity(bank, lastFour)
}

internal fun isSmsBalanceSource(sender: String?): Boolean =
    !sender.isNullOrBlank() && !sender.startsWith(USER_ENTERED_UPI_BALANCE_SOURCE)

private val snapshotRecency = compareBy<AccountBalanceSnapshot> { it.recordedAt }.thenBy { it.id }

/**
 * Builds the normal user-facing account list while retaining physical members in storage.
 * Each retained physical identity contributes its latest current value. Historical snapshots are
 * never summed, and duplicate database aliases with the same institution/type/last-four contribute
 * only once.
 */
fun consolidatedAccountProfiles(
    accounts: List<AccountProfile>,
    latestSmsBalances: Map<Long, AccountBalanceSnapshot> = emptyMap(),
): List<AccountProfile> {
    val accountsById = accounts.associateBy(AccountProfile::id)
    return accounts
        .groupBy { canonicalAccountId(accountsById, it.id) }
        .mapNotNull { (rootId, members) ->
            val root = accountsById[rootId] ?: return@mapNotNull null
            fun physicalIdentity(account: AccountProfile): String {
                if (account.type == AccountType.BANK_ACCOUNT) {
                    val bank = bankAccountIdentity(account) ?: return "account:${account.id}"
                    return "bank:${bank.institutionKey}:${bank.lastFour}"
                }
                val institution = BankSmsSupport.accountBankKey(account.institution, account.name)
                    ?: account.institution
                    ?.lowercase()
                    ?.replace(Regex("[^a-z0-9]+"), "-")
                    ?.trim('-')
                    ?.takeIf(String::isNotBlank)
                val hint = account.accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf(String::isNotBlank)
                return if (institution != null && hint != null) {
                    "${account.type.name}:$institution:$hint"
                } else {
                    "account:${account.id}"
                }
            }
            val physicalMembers = members.groupBy(::physicalIdentity).values
            val bankBalances = if (root.type == AccountType.BANK_ACCOUNT) {
                physicalMembers.map { aliases ->
                    val latestSms = if (aliases.size < 2) null else aliases.flatMap { alias ->
                        listOfNotNull(
                            latestSmsBalances[alias.id]?.takeIf { it.balanceMinor != null && isSmsBalanceSource(it.sender) },
                            alias.currentBalanceSnapshot()?.takeIf { isSmsBalanceSource(it.sender) },
                        )
                    }.maxWithOrNull(snapshotRecency)
                    latestSms ?: aliases.mapNotNull(AccountProfile::currentBalanceSnapshot).maxWithOrNull(snapshotRecency)
                }
            } else null
            fun latestValue(
                aliases: List<AccountProfile>,
                selector: (AccountProfile) -> Long?,
            ): Long? = aliases
                .asSequence()
                .filter { selector(it) != null }
                .maxWithOrNull(
                    compareBy<AccountProfile> { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                        .thenBy { it.id },
                )
                ?.let(selector)
            fun consolidatedValue(selector: (AccountProfile) -> Long?): Long? {
                val currentValues = physicalMembers.mapNotNull { latestValue(it, selector) }
                return currentValues.takeIf(List<Long>::isNotEmpty)?.sum()
            }
            fun completeConsolidatedValue(selector: (AccountProfile) -> Long?): Long? {
                val currentValues = physicalMembers.map { latestValue(it, selector) }
                return currentValues
                    .takeIf { values -> values.all { it != null } }
                    ?.filterNotNull()
                    ?.sum()
            }
            fun hasRelevantCurrentValue(account: AccountProfile): Boolean = when (root.type) {
                AccountType.BANK_ACCOUNT -> account.balanceMinor != null
                AccountType.CREDIT_CARD -> account.availableCreditMinor != null
                else -> account.balanceMinor != null || account.availableCreditMinor != null ||
                    account.creditLimitMinor != null
            }
            val compositeAvailabilityAt = if (members.size == 1) {
                root.availabilityFetchedAt
            } else {
                val physicalAvailability = bankBalances?.map { it?.recordedAt?.takeIf { day -> day > 0L } }
                    ?: physicalMembers.map { aliases ->
                    aliases.asSequence()
                        .filter(::hasRelevantCurrentValue)
                        .mapNotNull(AccountProfile::availabilityFetchedAt)
                        .maxOrNull()
                }
                if (physicalAvailability.all { it != null }) {
                    physicalAvailability.filterNotNull().minOrNull()
                } else {
                    null
                }
            }
            val primaryBankIdentity = bankAccountIdentity(root)?.takeIf { physicalMembers.size == 1 && members.size > 1 }
            val commonHint = primaryBankIdentity?.lastFour ?: members.map(AccountProfile::accountHint)
                .map { it?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .singleOrNull()
            val memberBankKeys = members.map {
                if (it.type == AccountType.BANK_ACCOUNT) recognizedAccountBank(it)
                else BankSmsSupport.accountBankKey(it.institution, it.name)
            }.distinct()
            val commonInstitution = memberBankKeys.singleOrNull()?.let { bankKey ->
                bankKey?.let(BankSmsSupport::institutionName)
            } ?: members.map(AccountProfile::institution)
                .map { it?.trim()?.takeIf(String::isNotBlank) }
                .distinctBy { it?.lowercase() }
                .singleOrNull()
            root.copy(
                accountHint = commonHint,
                institution = commonInstitution,
                balanceMinor = if (bankBalances != null) {
                    bankBalances.mapNotNull { it?.balanceMinor }.takeIf(List<Long>::isNotEmpty)?.sum()
                } else consolidatedValue(AccountProfile::balanceMinor),
                availableCreditMinor = consolidatedValue(AccountProfile::availableCreditMinor),
                creditLimitMinor = if (root.type == AccountType.CREDIT_CARD) {
                    completeConsolidatedValue(AccountProfile::creditLimitMinor)
                } else {
                    consolidatedValue(AccountProfile::creditLimitMinor)
                },
                availabilityFetchedAt = compositeAvailabilityAt,
                availabilitySender = if (primaryBankIdentity != null) {
                    bankBalances?.singleOrNull()?.sender?.takeIf(String::isNotBlank)
                } else root.availabilitySender.takeIf { members.size == 1 },
                mergedIntoAccountId = null,
                mergedMemberCount = members.size,
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AccountProfile::name).thenBy(AccountProfile::id))
}

private fun AccountProfile.currentBalanceSnapshot(): AccountBalanceSnapshot? {
    val value = balanceMinor ?: return null
    return AccountBalanceSnapshot(
        id = 0,
        accountId = id,
        balanceMinor = value,
        availableCreditMinor = null,
        creditLimitMinor = null,
        recordedAt = availabilityFetchedAt ?: 0,
        sender = availabilitySender.orEmpty(),
    )
}

private val MERGEABLE_ACCOUNT_TYPES = setOf(AccountType.BANK_ACCOUNT, AccountType.CREDIT_CARD)
