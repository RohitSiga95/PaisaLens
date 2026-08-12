package com.paisalens.app.data.model

import java.time.DayOfWeek
import java.util.Locale

/** A reorderable section on the Home dashboard. */
enum class HomeModule(
    val storageId: String,
    val label: String,
) {
    MONTHLY_SPEND("monthly_spend", "Monthly spend"),
    SPEND_OVERVIEW("spend_overview", "Spend overview"),
    SPENDING_BREAKDOWN("spending_breakdown", "Spending breakdown"),
    BANK_BALANCES("bank_balances", "Bank balances"),
    CREDIT_AVAILABLE("credit_available", "Credit available"),
    SAVINGS_GOALS("savings_goals", "Savings goals"),
    UPCOMING_COMMITMENTS("upcoming_commitments", "Upcoming commitments"),
    ;

    companion object {
        val defaultOrder: List<HomeModule> = listOf(
            MONTHLY_SPEND,
            SPEND_OVERVIEW,
            SPENDING_BREAKDOWN,
            BANK_BALANCES,
            CREDIT_AVAILABLE,
            SAVINGS_GOALS,
            UPCOMING_COMMITMENTS,
        )

        fun fromStorageId(value: String?): HomeModule? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.storageId == normalized }
        }
    }
}

/**
 * The ordered list is also the visibility list: omitted modules are hidden.
 * At least one module is retained so a damaged preference can never produce a blank Home screen.
 */
data class HomeLayoutConfiguration(
    val orderedVisibleModules: List<HomeModule> = HomeModule.defaultOrder,
) {
    fun normalized(): HomeLayoutConfiguration {
        val safeModules = orderedVisibleModules.distinct()
        return copy(
            orderedVisibleModules = safeModules.ifEmpty { HomeModule.defaultOrder },
        )
    }

    fun isVisible(module: HomeModule): Boolean = module in orderedVisibleModules

    fun withVisibility(module: HomeModule, visible: Boolean): HomeLayoutConfiguration {
        val current = normalized().orderedVisibleModules
        val updated = if (visible) {
            if (module in current) current else current + module
        } else {
            current - module
        }
        return copy(
            orderedVisibleModules = updated.ifEmpty { listOf(HomeModule.MONTHLY_SPEND) },
        )
    }

    fun move(module: HomeModule, toIndex: Int): HomeLayoutConfiguration {
        val current = normalized().orderedVisibleModules
        if (module !in current || current.size < 2) return copy(orderedVisibleModules = current)
        val reordered = current.toMutableList().apply {
            remove(module)
            add(toIndex.coerceIn(0, size), module)
        }
        return copy(orderedVisibleModules = reordered)
    }

    fun toStorageString(): String = normalized().orderedVisibleModules.joinToString(",") { it.storageId }

    companion object {
        fun fromStorageString(value: String?): HomeLayoutConfiguration {
            if (value.isNullOrBlank()) return HomeLayoutConfiguration()
            val parsed = value
                .split(',')
                .mapNotNull(HomeModule::fromStorageId)
                .distinct()
            return HomeLayoutConfiguration(
                orderedVisibleModules = parsed.ifEmpty { HomeModule.defaultOrder },
            )
        }
    }
}

enum class NotificationDigestFrequency(
    val storageId: String,
    val label: String,
) {
    DAILY("daily", "Daily"),
    WEEKLY("weekly", "Weekly"),
    ;

    companion object {
        fun fromStorageId(value: String?): NotificationDigestFrequency {
            val normalized = value?.trim()?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.storageId == normalized } ?: DAILY
        }
    }
}

/** Private by default: notification text omits all monetary values unless the user opts in. */
data class NotificationDigestConfiguration(
    val enabled: Boolean = false,
    val frequency: NotificationDigestFrequency = NotificationDigestFrequency.DAILY,
    val hour: Int = DEFAULT_HOUR,
    val weekday: DayOfWeek = DayOfWeek.MONDAY,
    val showAmounts: Boolean = false,
) {
    fun normalized(): NotificationDigestConfiguration = copy(hour = hour.coerceIn(0, 23))

    companion object {
        const val DEFAULT_HOUR = 20

        fun safeWeekday(value: String?): DayOfWeek = runCatching {
            DayOfWeek.valueOf(value?.trim()?.uppercase(Locale.ROOT).orEmpty())
        }.getOrDefault(DayOfWeek.MONDAY)
    }
}
