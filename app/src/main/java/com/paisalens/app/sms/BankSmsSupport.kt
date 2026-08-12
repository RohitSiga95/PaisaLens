package com.paisalens.app.sms

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import java.util.Locale

data class BankSmsCommand(
    val destination: String,
    val message: String,
    val bankName: String,
)

object BankSmsSupport {
    private const val SBI = "sbi"
    private const val HDFC = "hdfc"
    private const val IDFC = "idfc"
    private const val SIB = "sib"
    private const val ICICI = "icici"
    private const val AXIS = "axis"
    private const val KOTAK = "kotak"
    private const val PNB = "pnb"
    private const val BOB = "bob"
    private const val CANARA = "canara"
    private const val YES = "yes"
    private const val INDUSIND = "indusind"
    private const val UNION = "union"
    private const val BOI = "boi"
    private const val FEDERAL = "federal"
    private const val RBL = "rbl"
    private const val AU = "au"
    private const val DBS = "dbs"
    private const val HSBC = "hsbc"
    private const val STANDARD_CHARTERED = "standard_chartered"

    fun bankKey(value: String): String? {
        val tokens = value.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        val normalized = tokens.joinToString("")
        val hasTransportPrefix = tokens.firstOrNull() in SMS_TRANSPORT_PREFIXES
        val senderPayload = normalized
            .removePrefix(tokens.firstOrNull()?.takeIf { hasTransportPrefix }.orEmpty())
            .removeSuffix("s")
        val candidates = buildSet {
            add(normalized)
            add(senderPayload)
            addAll(tokens)
        }
        return BANK_ALIASES.entries.firstOrNull { (_, aliases) -> candidates.any(aliases::contains) }?.key
            ?: DISTINCTIVE_SENDER_ROOTS.entries.firstOrNull { (_, root) ->
                hasTransportPrefix &&
                senderPayload.startsWith(root) && senderPayload.length in root.length..(root.length + 5)
            }?.key
    }

    fun institutionName(bankKey: String): String = when (bankKey) {
        SBI -> "State Bank of India"
        "sbi_card" -> "SBI Card"
        HDFC -> "HDFC Bank"
        IDFC -> "IDFC FIRST Bank"
        SIB -> "South Indian Bank"
        ICICI -> "ICICI Bank"
        AXIS -> "Axis Bank"
        KOTAK -> "Kotak Mahindra Bank"
        PNB -> "Punjab National Bank"
        BOB -> "Bank of Baroda"
        CANARA -> "Canara Bank"
        YES -> "YES Bank"
        INDUSIND -> "IndusInd Bank"
        UNION -> "Union Bank of India"
        BOI -> "Bank of India"
        FEDERAL -> "Federal Bank"
        RBL -> "RBL Bank"
        AU -> "AU Small Finance Bank"
        DBS -> "DBS Bank"
        HSBC -> "HSBC"
        STANDARD_CHARTERED -> "Standard Chartered Bank"
        else -> bankKey.replace('_', ' ').replaceFirstChar(Char::titlecase)
    }

    /**
     * Returns a stable, user-facing institution tag only when [value] contains a
     * recognised bank/card alias. This intentionally does not turn an arbitrary
     * sender ID into a bank name.
     */
    fun institutionNameOrNull(value: String): String? = bankKey(value)?.let(::institutionName)

    fun accountBankKey(institution: String?, accountName: String?): String? =
        bankKey(institution.orEmpty()) ?: bankKey(accountName.orEmpty())

    fun commandFor(account: AccountProfile): BankSmsCommand? {
        val key = accountBankKey(account.institution, account.name)
            ?: return null
        return when {
            account.type == AccountType.CREDIT_CARD && key in setOf(SBI, "sbi_card") -> {
                val lastFour = account.accountHint ?: return null
                BankSmsCommand(
                    destination = "5676791",
                    message = "AVAIL $lastFour",
                    bankName = "SBI Card",
                )
            }
            account.type == AccountType.BANK_ACCOUNT && key == SBI -> BankSmsCommand(
                destination = "919223766666",
                message = "BAL",
                bankName = "State Bank of India",
            )
            account.type == AccountType.BANK_ACCOUNT && key == HDFC -> BankSmsCommand(
                destination = "5676712",
                message = "BAL",
                bankName = "HDFC Bank",
            )
            account.type == AccountType.BANK_ACCOUNT && key == ICICI -> BankSmsCommand(
                destination = "9215676766",
                message = "IBAL",
                bankName = "ICICI Bank",
            )
            account.type == AccountType.BANK_ACCOUNT && key == AXIS -> BankSmsCommand(
                destination = "56161600",
                message = "BAL",
                bankName = "Axis Bank",
            )
            else -> null
        }
    }

    private val SMS_TRANSPORT_PREFIXES = setOf(
        "ad", "ax", "bz", "jd", "jm", "vk", "vm", "tm", "cp", "bp", "hp", "qp",
    )

    private val BANK_ALIASES = linkedMapOf(
        "sbi_card" to setOf("sbicard", "sbicrd", "tatacard", "tatacrd"),
        SIB to setOf("southindianbank", "sibank", "sibbk", "sib"),
        SBI to setOf("statebankofindia", "sbiinb", "sbinbk", "sbi"),
        HDFC to setOf("hdfc", "hdfcbk", "hdfcbank", "hdfccrd", "hdfccard"),
        IDFC to setOf("idfcfirst", "idfcfirstbank", "idfc", "idfcbk", "idfcbank", "idfccrd"),
        ICICI to setOf("icici", "icicib", "icicibk", "icicibank", "icicicrd"),
        AXIS to setOf("axis", "axisbk", "axisbank", "axiscrd", "axiscard"),
        KOTAK to setOf("kotakmahindra", "kotakmahindrabank", "kotak", "kotakbk"),
        PNB to setOf("punjabnationalbank", "pnbsms", "pnb", "pnbbk"),
        BOB to setOf("bankofbaroda", "bobtxn", "bobank"),
        CANARA to setOf("canarabank", "canbnk", "canara"),
        YES to setOf("yesbank", "yesbnk"),
        INDUSIND to setOf("indusind", "indusindbank", "indbnk"),
        UNION to setOf("unionbank", "unionbankofindia", "unionb"),
        BOI to setOf("bankofindia", "boiind"),
        FEDERAL to setOf("federalbank", "fedbnk"),
        RBL to setOf("rblbank", "rblbnk"),
        AU to setOf("aubank", "ausfb", "ausmallfinancebank"),
        DBS to setOf("dbsbank", "dbsbnk", "dbs"),
        HSBC to setOf("hsbc", "hsbcbank"),
        STANDARD_CHARTERED to setOf("standardchartered", "standardcharteredbank", "scbank"),
    )

    // DLT sender suffixes change over time. Prefix matching is limited to long,
    // distinctive roots; short/collision-prone names such as SBI, SIB, AU and DBS
    // remain exact aliases so ordinary user labels cannot be mistaken for a bank.
    private val DISTINCTIVE_SENDER_ROOTS = linkedMapOf(
        HDFC to "hdfc",
        IDFC to "idfc",
        ICICI to "icici",
        AXIS to "axis",
        KOTAK to "kotak",
        INDUSIND to "indusind",
        FEDERAL to "federal",
        STANDARD_CHARTERED to "standardchartered",
    )
}
