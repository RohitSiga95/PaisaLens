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
    private const val ICICI = "icici"
    private const val AXIS = "axis"

    fun bankKey(value: String): String? {
        val normalized = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
        return when {
            "sbicard" in normalized || "tatacard" in normalized -> "sbi_card"
            "sbi" in normalized || "sbiinb" in normalized || "statebankofindia" in normalized -> SBI
            "hdfc" in normalized -> HDFC
            "icici" in normalized -> ICICI
            "axis" in normalized -> AXIS
            else -> null
        }
    }

    fun institutionName(bankKey: String): String = when (bankKey) {
        SBI, "sbi_card" -> "State Bank of India"
        HDFC -> "HDFC Bank"
        ICICI -> "ICICI Bank"
        AXIS -> "Axis Bank"
        else -> bankKey.replace('_', ' ').replaceFirstChar(Char::titlecase)
    }

    fun commandFor(account: AccountProfile): BankSmsCommand? {
        val key = bankKey(listOfNotNull(account.institution, account.name).joinToString(" "))
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
}
