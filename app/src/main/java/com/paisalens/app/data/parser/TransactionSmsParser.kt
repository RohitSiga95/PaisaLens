package com.paisalens.app.data.parser

import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

class TransactionSmsParser {
    fun parse(
        sender: String,
        body: String,
        timestamp: Long,
        messageId: String? = null,
    ): ParsedTransaction? {
        val normalized = body.lowercase().replace('\n', ' ')
        if (shouldIgnore(normalized)) return null

        val amountMinor = extractAmountMinor(body) ?: return null
        if (amountMinor <= 0) return null

        val type = detectType(normalized) ?: return null
        val source = detectSource(normalized)
        val merchantMatch = extractMerchant(body, sender, type)
        val merchant = merchantMatch.name
        val category = when (type) {
            TransactionType.INCOME -> ExpenseCategory.INCOME
            TransactionType.TRANSFER -> ExpenseCategory.TRANSFER
            else -> categorize("$merchant $normalized")
        }

        val reviewReasons = buildList {
            if (merchantMatch.usedSenderFallback) add("Merchant name could not be identified confidently")
            if (category == ExpenseCategory.OTHER && type == TransactionType.EXPENSE) {
                add("Choose a category for this expense")
            }
        }

        return ParsedTransaction(
            sourceMessageId = messageId ?: stableId(sender, body, timestamp),
            amountMinor = amountMinor,
            merchant = merchant,
            accountHint = extractAccountHint(body),
            category = category,
            type = type,
            occurredAt = timestamp,
            source = source,
            sender = sender.ifBlank { "Transaction alert" },
            rawMessage = body,
            reviewStatus = if (reviewReasons.isEmpty()) {
                ReviewStatus.CONFIRMED
            } else {
                ReviewStatus.NEEDS_REVIEW
            },
            reviewReason = reviewReasons.joinToString(" · ").takeIf(String::isNotBlank),
        )
    }

    private fun shouldIgnore(text: String): Boolean {
        val alwaysIgnore = listOf(
            "one time password",
            " otp ",
            "otp is",
            "verification code",
            "login code",
            "payment due",
            "minimum amount due",
            "total amount due",
            "statement is ready",
            "monthly statement",
            "collect request",
            "payment request",
        )
        return alwaysIgnore.any { text.contains(it) }
    }

    private fun detectType(text: String): TransactionType? {
        if (
            text.contains("credit card bill") ||
            text.contains("card bill payment") ||
            (text.contains("payment received") && text.contains("card")) ||
            (text.contains("towards") && text.contains("credit card")) ||
            text.contains("self transfer") ||
            text.contains("between your accounts") ||
            text.contains("to your own account")
        ) {
            return TransactionType.TRANSFER
        }

        val refunds = listOf("refunded", "refund of", "reversed", "reversal", "cashback")
        if (refunds.any(text::contains)) return TransactionType.REFUND

        val expenses = listOf(
            "debited",
            "debit of",
            "spent",
            "you paid",
            "has been paid",
            "purchase of",
            "withdrawn",
            "cash withdrawal",
            "sent via upi",
            "transferred to",
            "txn of",
            "transaction of",
            "charged",
        )
        val incomes = listOf(
            "credited",
            "credit of",
            "received",
            "deposited",
            "salary",
            "transferred from",
        )

        return when {
            expenses.any(text::contains) -> TransactionType.EXPENSE
            incomes.any(text::contains) -> TransactionType.INCOME
            else -> null
        }
    }

    private fun extractAmountMinor(body: String): Long? {
        for (pattern in amountPatterns) {
            val amountText = pattern.find(body)?.groupValues?.getOrNull(1) ?: continue
            val value = amountText.replace(",", "").toBigDecimalOrNull() ?: continue
            return value
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }
        return null
    }

    private fun extractMerchant(
        body: String,
        sender: String,
        type: TransactionType,
    ): MerchantMatch {
        val patterns = if (type == TransactionType.INCOME) incomeMerchantPatterns else expenseMerchantPatterns
        patterns.forEach { pattern ->
            val match = pattern.find(body)?.groupValues?.getOrNull(1)
            val cleaned = match?.let(::cleanMerchant)
            if (!cleaned.isNullOrBlank() && cleaned.length >= 2) {
                return MerchantMatch(cleaned, usedSenderFallback = false)
            }
        }

        val fallback = sender
            .replace(Regex("(?i)^(?:AD|AX|BZ|JD|JM|VK|VM|TM|CP|BP|HP|QP)-"), "")
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .trim()
            .ifBlank { "Transaction" }
            .lowercase()
            .split(" ")
            .filter(String::isNotBlank)
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
        return MerchantMatch(fallback, usedSenderFallback = true)
    }

    private fun cleanMerchant(value: String): String = value
        .replace(Regex("(?i)\\b(?:upi|ref|txn|transaction|using|via|on|avl|available|balance|a/c|acct)\\b.*$"), "")
        .replace(Regex("[*#]"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.', ',', '-', ':')
        .take(36)
        .lowercase()
        .split(" ")
        .filter(String::isNotBlank)
        .joinToString(" ") { token ->
            if (token.contains('@')) token else token.replaceFirstChar(Char::titlecase)
        }

    private fun extractAccountHint(body: String): String? {
        val raw = accountPattern.find(body)?.groupValues?.getOrNull(1) ?: return null
        val digits = raw.filter(Char::isDigit)
        return digits.takeLast(4).takeIf { it.isNotBlank() }
    }

    fun categorize(text: String): ExpenseCategory {
        val normalized = text.lowercase()
        return categoryKeywords.entries.firstOrNull { (_, words) -> words.any(normalized::contains) }?.key
            ?: ExpenseCategory.OTHER
    }

    private fun detectSource(text: String): TransactionSource = when {
        text.contains("upi") || text.contains("vpa") -> TransactionSource.UPI
        text.contains("wallet") || text.contains("paytm") || text.contains("mobikwik") -> TransactionSource.WALLET
        text.contains("card") || text.contains("pos") -> TransactionSource.CARD
        else -> TransactionSource.BANK
    }

    private fun stableId(sender: String, body: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sender|$body|$timestamp".toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        data class MerchantMatch(
            val name: String,
            val usedSenderFallback: Boolean,
        )

        val amountPatterns = listOf(
            Regex("(?i)(?:₹|INR|Rs\\.?|Rupees?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
            Regex("(?i)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:INR|Rupees?)"),
        )

        val expenseMerchantPatterns = listOf(
            Regex("(?i)\\b(?:at|to)\\s+([A-Za-z0-9@&._' -]{2,60})"),
            Regex("(?i)\\bmerchant[: ]+([A-Za-z0-9@&._' -]{2,60})"),
            Regex("(?i)\\b(?:paid|sent|transferred)\\s+(?:to\\s+)?([A-Za-z0-9@&._' -]{2,60})"),
        )

        val incomeMerchantPatterns = listOf(
            Regex("(?i)\\b(?:from|by)\\s+([A-Za-z0-9@&._' -]{2,60})"),
            Regex("(?i)\\bsender[: ]+([A-Za-z0-9@&._' -]{2,60})"),
        )

        val accountPattern = Regex(
            "(?i)(?:a/c|acct|account|card)(?:\\s*(?:no\\.?|number|ending|xx|x{2,}))?\\s*[:.-]?\\s*([xX*0-9-]{3,20})",
        )

        val categoryKeywords = linkedMapOf(
            ExpenseCategory.FOOD to listOf(
                "swiggy", "zomato", "restaurant", "cafe", "coffee", "food", "dominos", "pizza", "starbucks",
            ),
            ExpenseCategory.GROCERIES to listOf(
                "grocery", "groceries", "bigbasket", "blinkit", "zepto", "dmart", "supermarket", "fresh",
            ),
            ExpenseCategory.TRANSPORT to listOf(
                "uber", "ola", "rapido", "metro", "petrol", "diesel", "fuel", "parking", "toll", "fastag",
            ),
            ExpenseCategory.BILLS to listOf(
                "electric", "electricity", "utility", "broadband", "internet", "airtel", "jio", "vi recharge", "gas bill", "water bill",
            ),
            ExpenseCategory.ENTERTAINMENT to listOf(
                "netflix", "spotify", "prime video", "hotstar", "bookmyshow", "cinema", "pvr", "gaming",
            ),
            ExpenseCategory.HEALTH to listOf(
                "pharmacy", "medical", "hospital", "clinic", "apollo", "1mg", "pharmeasy", "doctor", "dental",
            ),
            ExpenseCategory.EDUCATION to listOf(
                "school", "college", "university", "course", "udemy", "book", "tuition", "education",
            ),
            ExpenseCategory.TRAVEL to listOf(
                "airlines", "airways", "flight", "hotel", "makemytrip", "goibibo", "irctc", "railway", "resort",
            ),
            ExpenseCategory.CASH to listOf("atm", "cash withdrawal", "withdrawn"),
            ExpenseCategory.SHOPPING to listOf(
                "amazon", "flipkart", "myntra", "ajio", "shopping", "retail", "store", "mall", "meesho",
            ),
            ExpenseCategory.TRANSFER to listOf("transfer", "credit card bill", "card payment"),
        )
    }
}
