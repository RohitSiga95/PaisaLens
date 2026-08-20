package com.paisalens.app.data.model

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationModelsTest {
    @Test
    fun `legacy layout gains daily snapshot modules once while v3 can hide them`() {
        val migrated = HomeLayoutConfiguration.fromStorageString("monthly_spend,bank_balances")
        assertEquals(
            listOf(
                HomeModule.FINANCIAL_PULSE,
                HomeModule.NEEDS_ATTENTION,
                HomeModule.MONTHLY_SPEND,
                HomeModule.BANK_BALANCES,
                HomeModule.MONEY_TIMELINE,
                HomeModule.BUDGET_PACE,
                HomeModule.CARD_HEALTH,
            ),
            migrated.orderedVisibleModules,
        )

        val hidden = HomeLayoutConfiguration(
            listOf(HomeModule.MONTHLY_SPEND, HomeModule.BANK_BALANCES),
        )
        assertEquals(hidden, HomeLayoutConfiguration.fromStorageString(hidden.toStorageString()))
    }

    @Test
    fun `home layout uses complete default order when no stored value exists`() {
        assertEquals(
            HomeModule.defaultOrder,
            HomeLayoutConfiguration.fromStorageString(null).orderedVisibleModules,
        )
    }

    @Test
    fun `home layout safely drops unknown and duplicate stored modules`() {
        val parsed = HomeLayoutConfiguration.fromStorageString(
            "bank_balances,future_module,monthly_spend,bank_balances",
        )

        assertEquals(
            listOf(
                HomeModule.FINANCIAL_PULSE,
                HomeModule.NEEDS_ATTENTION,
                HomeModule.BANK_BALANCES,
                HomeModule.MONTHLY_SPEND,
                HomeModule.MONEY_TIMELINE,
                HomeModule.BUDGET_PACE,
                HomeModule.CARD_HEALTH,
            ),
            parsed.orderedVisibleModules,
        )
    }

    @Test
    fun `home layout falls back when stored value contains no known modules`() {
        val parsed = HomeLayoutConfiguration.fromStorageString("retired_widget,broken")

        assertEquals(HomeModule.defaultOrder, parsed.orderedVisibleModules)
    }

    @Test
    fun `visibility and reordering retain a non-empty deterministic layout`() {
        val initial = HomeLayoutConfiguration(listOf(HomeModule.MONTHLY_SPEND, HomeModule.BANK_BALANCES))
        val moved = initial.move(HomeModule.BANK_BALANCES, 0)
        val oneLeft = moved.withVisibility(HomeModule.BANK_BALANCES, false)
        val protected = oneLeft.withVisibility(HomeModule.MONTHLY_SPEND, false)

        assertEquals(
            listOf(HomeModule.BANK_BALANCES, HomeModule.MONTHLY_SPEND),
            moved.orderedVisibleModules,
        )
        assertEquals(listOf(HomeModule.MONTHLY_SPEND), oneLeft.orderedVisibleModules)
        assertEquals(listOf(HomeModule.FINANCIAL_PULSE), protected.orderedVisibleModules)
    }

    @Test
    fun `home snapshot style and preset survive storage round trip`() {
        val configured = HomeLayoutConfiguration.forPreset(
            preset = HomeDashboardPreset.DEBT_FOCUS,
            density = HomeDashboardDensity.COMFORTABLE,
            heroMetric = HomeHeroMetric.AVAILABLE_CASH,
        )

        val restored = HomeLayoutConfiguration.fromStorageString(configured.toStorageString())

        assertEquals(configured, restored)
        assertEquals(HomeDashboardPreset.DEBT_FOCUS, restored.matchingPreset())
    }

    @Test
    fun `digest defaults are private and disabled`() {
        val configuration = NotificationDigestConfiguration()

        assertFalse(configuration.enabled)
        assertFalse(configuration.showAmounts)
        assertEquals(NotificationDigestFrequency.DAILY, configuration.frequency)
        assertEquals(20, configuration.hour)
        assertEquals(DayOfWeek.MONDAY, configuration.weekday)
    }

    @Test
    fun `digest parser and normalization safely handle legacy corruption`() {
        assertEquals(
            NotificationDigestFrequency.DAILY,
            NotificationDigestFrequency.fromStorageId("unexpected"),
        )
        assertEquals(DayOfWeek.MONDAY, NotificationDigestConfiguration.safeWeekday("FUNDAY"))
        assertEquals(23, NotificationDigestConfiguration(hour = 81).normalized().hour)
        assertTrue(NotificationDigestConfiguration(hour = -2).normalized().hour == 0)
    }
}
