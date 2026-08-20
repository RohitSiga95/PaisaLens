package com.paisalens.app.data.model

import java.time.DayOfWeek
import java.util.Locale

/** A reorderable section on the Home dashboard. */
enum class HomeModule(
    val storageId: String,
    val label: String,
) {
    FINANCIAL_PULSE("financial_pulse", "Financial pulse"),
    NEEDS_ATTENTION("needs_attention", "Needs your attention"),
    MONEY_TIMELINE("money_timeline", "Next 14 days"),
    BUDGET_PACE("budget_pace", "Budget pace"),
    CARD_HEALTH("card_health", "Card health"),
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
            FINANCIAL_PULSE,
            NEEDS_ATTENTION,
            MONEY_TIMELINE,
            BUDGET_PACE,
            CARD_HEALTH,
            SPENDING_BREAKDOWN,
            BANK_BALANCES,
            SAVINGS_GOALS,
        )

        /** Includes legacy and specialist modules which are hidden by the daily-first default. */
        val customizationOrder: List<HomeModule> = defaultOrder + entries.filterNot(defaultOrder::contains)

        fun fromStorageId(value: String?): HomeModule? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.storageId == normalized }
        }
    }
}

enum class HomeDashboardDensity(
    val storageId: String,
    val label: String,
) {
    COMPACT("compact", "Compact"),
    COMFORTABLE("comfortable", "Comfortable"),
    ;

    companion object {
        fun fromStorageId(value: String?): HomeDashboardDensity =
            entries.firstOrNull { it.storageId == value?.trim()?.lowercase(Locale.ROOT) } ?: COMPACT
    }
}

enum class HomeHeroMetric(
    val storageId: String,
    val label: String,
) {
    SAFE_TO_SPEND("safe_to_spend", "Safe to spend"),
    AVAILABLE_CASH("available_cash", "Available cash"),
    MONTHLY_SPEND("monthly_spend", "Monthly spending"),
    ;

    companion object {
        fun fromStorageId(value: String?): HomeHeroMetric =
            entries.firstOrNull { it.storageId == value?.trim()?.lowercase(Locale.ROOT) } ?: SAFE_TO_SPEND
    }
}

enum class HomeDashboardPreset(
    val label: String,
) {
    EVERYDAY("Everyday"),
    BUDGET_FOCUS("Budget focus"),
    DEBT_FOCUS("Debt focus"),
    MINIMAL("Minimal"),
}

/**
 * The ordered list is also the visibility list: omitted modules are hidden.
 * At least one module is retained so a damaged preference can never produce a blank Home screen.
 */
data class HomeLayoutConfiguration(
    val orderedVisibleModules: List<HomeModule> = HomeModule.defaultOrder,
    val density: HomeDashboardDensity = HomeDashboardDensity.COMPACT,
    val heroMetric: HomeHeroMetric = HomeHeroMetric.SAFE_TO_SPEND,
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
            orderedVisibleModules = updated.ifEmpty { listOf(HomeModule.FINANCIAL_PULSE) },
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

    fun toStorageString(): String = normalized().let { safe ->
        STORAGE_VERSION_PREFIX + listOf(
            safe.density.storageId,
            safe.heroMetric.storageId,
            safe.orderedVisibleModules.joinToString(",") { it.storageId },
        ).joinToString(STORAGE_SECTION_SEPARATOR)
    }

    fun matchingPreset(): HomeDashboardPreset? = HomeDashboardPreset.entries.firstOrNull { preset ->
        orderedVisibleModules == modulesForPreset(preset)
    }

    companion object {
        fun fromStorageString(value: String?): HomeLayoutConfiguration {
            if (value.isNullOrBlank()) return HomeLayoutConfiguration()
            if (value.startsWith(STORAGE_VERSION_PREFIX)) {
                val sections = value.removePrefix(STORAGE_VERSION_PREFIX).split(STORAGE_SECTION_SEPARATOR, limit = 3)
                val parsed = sections.getOrNull(2)
                    ?.split(',')
                    ?.mapNotNull(HomeModule::fromStorageId)
                    ?.distinct()
                    .orEmpty()
                return HomeLayoutConfiguration(
                    orderedVisibleModules = parsed.ifEmpty { HomeModule.defaultOrder },
                    density = HomeDashboardDensity.fromStorageId(sections.getOrNull(0)),
                    heroMetric = HomeHeroMetric.fromStorageId(sections.getOrNull(1)),
                )
            }

            val isV2Format = value.startsWith(PREVIOUS_STORAGE_VERSION_PREFIX)
            val rawValue = if (isV2Format) value.removePrefix(PREVIOUS_STORAGE_VERSION_PREFIX) else value
            val parsed = rawValue
                .split(',')
                .mapNotNull(HomeModule::fromStorageId)
                .distinct()
                .let { modules ->
                    if (modules.isEmpty()) return@let modules
                    val withAttention = if (!isV2Format && HomeModule.NEEDS_ATTENTION !in modules) {
                        listOf(HomeModule.NEEDS_ATTENTION) + modules
                    } else modules
                    // The v3 snapshot modules are inserted once. A v3 layout can hide them intentionally.
                    (listOf(HomeModule.FINANCIAL_PULSE) + withAttention + listOf(
                        HomeModule.MONEY_TIMELINE,
                        HomeModule.BUDGET_PACE,
                        HomeModule.CARD_HEALTH,
                    ).filterNot(withAttention::contains)).distinct()
                }
            return HomeLayoutConfiguration(
                orderedVisibleModules = parsed.ifEmpty { HomeModule.defaultOrder },
            )
        }

        fun forPreset(
            preset: HomeDashboardPreset,
            density: HomeDashboardDensity = HomeDashboardDensity.COMPACT,
            heroMetric: HomeHeroMetric = HomeHeroMetric.SAFE_TO_SPEND,
        ): HomeLayoutConfiguration = HomeLayoutConfiguration(
            orderedVisibleModules = modulesForPreset(preset),
            density = density,
            heroMetric = heroMetric,
        )

        private fun modulesForPreset(preset: HomeDashboardPreset): List<HomeModule> = when (preset) {
            HomeDashboardPreset.EVERYDAY -> HomeModule.defaultOrder
            HomeDashboardPreset.BUDGET_FOCUS -> listOf(
                HomeModule.FINANCIAL_PULSE,
                HomeModule.NEEDS_ATTENTION,
                HomeModule.BUDGET_PACE,
                HomeModule.MONEY_TIMELINE,
                HomeModule.SPENDING_BREAKDOWN,
                HomeModule.SAVINGS_GOALS,
            )
            HomeDashboardPreset.DEBT_FOCUS -> listOf(
                HomeModule.FINANCIAL_PULSE,
                HomeModule.NEEDS_ATTENTION,
                HomeModule.CARD_HEALTH,
                HomeModule.MONEY_TIMELINE,
                HomeModule.CREDIT_AVAILABLE,
                HomeModule.BANK_BALANCES,
            )
            HomeDashboardPreset.MINIMAL -> listOf(
                HomeModule.FINANCIAL_PULSE,
                HomeModule.NEEDS_ATTENTION,
                HomeModule.MONEY_TIMELINE,
            )
        }

        private const val STORAGE_VERSION_PREFIX = "v3:"
        private const val PREVIOUS_STORAGE_VERSION_PREFIX = "v2:"
        private const val STORAGE_SECTION_SEPARATOR = "|"
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

/** Independent alert categories let users keep only reminders that are useful to them. */
enum class ActionableAlertCategory(
    val storageId: String,
    val label: String,
    val description: String,
) {
    CARD_BILL_DUE(
        "card_bill_due",
        "Credit-card bills",
        "Current statement dues that are approaching or overdue.",
    ),
    BILL_DUE(
        "bill_due",
        "Bills and AutoPay",
        "Upcoming bills, subscriptions, and scheduled payments.",
    ),
    BUDGET_THRESHOLD(
        "budget_threshold",
        "Budget pace",
        "Budgets that reach the warning level you choose.",
    ),
    CREDIT_UTILIZATION(
        "credit_utilization",
        "Credit utilisation",
        "Cards that reach a high utilisation level.",
    ),
    LOW_CASH_FLOW(
        "low_cash_flow",
        "Low forecast balance",
        "A projected balance that falls below your safety floor.",
    ),
    OVERDUE_REIMBURSEMENT(
        "overdue_reimbursement",
        "Expected reimbursements",
        "Split expenses that have remained unsettled for two weeks.",
    ),
    NEEDS_ATTENTION(
        "needs_attention",
        "Review reminders",
        "Important local items that still need confirmation.",
    ),
    ;

    companion object {
        fun fromStorageId(value: String?): ActionableAlertCategory? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.storageId == normalized }
        }
    }
}

/**
 * Opt-in, local-only money alerts. Lock-screen text and monetary values remain private by default.
 * Thresholds use basis points so preference storage never relies on floating-point values.
 */
data class ActionableAlertsConfiguration(
    val enabled: Boolean = false,
    val enabledCategories: Set<ActionableAlertCategory> = ActionableAlertCategory.entries.toSet(),
    val evaluationHour: Int = DEFAULT_EVALUATION_HOUR,
    val dueWindowDays: Int = DEFAULT_DUE_WINDOW_DAYS,
    val budgetThresholdBasisPoints: Int = DEFAULT_BUDGET_THRESHOLD_BASIS_POINTS,
    val utilizationThresholdBasisPoints: Int = DEFAULT_UTILIZATION_THRESHOLD_BASIS_POINTS,
    val lowBalanceThresholdMinor: Long = 0L,
    val showAmounts: Boolean = false,
    val genericLockScreenText: Boolean = true,
    val minimumRepeatHours: Int = DEFAULT_MINIMUM_REPEAT_HOURS,
) {
    fun normalized(): ActionableAlertsConfiguration = copy(
        enabledCategories = enabledCategories.intersect(ActionableAlertCategory.entries.toSet()),
        evaluationHour = evaluationHour.coerceIn(0, 23),
        dueWindowDays = dueWindowDays.coerceIn(0, 30),
        budgetThresholdBasisPoints = budgetThresholdBasisPoints.coerceIn(5_000, 10_000),
        utilizationThresholdBasisPoints = utilizationThresholdBasisPoints.coerceIn(3_000, 10_000),
        lowBalanceThresholdMinor = lowBalanceThresholdMinor.coerceAtLeast(0L),
        minimumRepeatHours = minimumRepeatHours.coerceIn(6, 168),
    )

    fun isEnabled(category: ActionableAlertCategory): Boolean =
        enabled && category in enabledCategories

    companion object {
        const val DEFAULT_EVALUATION_HOUR = 9
        const val DEFAULT_DUE_WINDOW_DAYS = 3
        const val DEFAULT_BUDGET_THRESHOLD_BASIS_POINTS = 9_000
        const val DEFAULT_UTILIZATION_THRESHOLD_BASIS_POINTS = 7_500
        const val DEFAULT_MINIMUM_REPEAT_HOURS = 24
    }
}

/** The persisted default is separate from the temporary eye-button override for this app session. */
data class PrivacyModeConfiguration(
    val defaultEnabled: Boolean = false,
    val protectScreenCapture: Boolean = true,
)
