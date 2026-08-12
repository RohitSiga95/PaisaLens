package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeConfigurationTest {
    @Test
    fun defaultsPreserveTheExistingDarkPaisaLensAppearance() {
        val configuration = AppThemeConfiguration()

        assertEquals(AppThemeStyle.MATERIAL, configuration.style)
        assertEquals(AppThemeMode.DARK, configuration.mode)
        assertEquals(AppThemePalette.PAISALENS, configuration.palette)
        assertTrue(configuration.isDark(systemInDarkTheme = false))
    }

    @Test
    fun appearanceModeFollowsSystemOnlyWhenSelected() {
        val system = AppThemeConfiguration(mode = AppThemeMode.SYSTEM)
        val light = AppThemeConfiguration(mode = AppThemeMode.LIGHT)

        assertFalse(system.isDark(systemInDarkTheme = false))
        assertTrue(system.isDark(systemInDarkTheme = true))
        assertFalse(light.isDark(systemInDarkTheme = true))
    }

    @Test
    fun amoledAlwaysResolvesToDark() {
        val configuration = AppThemeConfiguration(
            style = AppThemeStyle.AMOLED,
            mode = AppThemeMode.LIGHT,
        )

        assertTrue(configuration.isDark(systemInDarkTheme = false))
    }

    @Test
    fun unknownStoredValuesUseSafeDefaults() {
        assertEquals(AppThemeStyle.MATERIAL, AppThemeStyle.fromStorageId("future-style"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorageId("future-mode"))
        assertEquals(AppThemePalette.PAISALENS, AppThemePalette.fromStorageId("future-palette"))
    }

    @Test
    fun storageIdsAreUniqueAndRoundTrip() {
        assertEquals(AppThemeStyle.entries.size, AppThemeStyle.entries.map { it.storageId }.toSet().size)
        assertEquals(AppThemeMode.entries.size, AppThemeMode.entries.map { it.storageId }.toSet().size)
        assertEquals(AppThemePalette.entries.size, AppThemePalette.entries.map { it.storageId }.toSet().size)

        AppThemeStyle.entries.forEach { assertEquals(it, AppThemeStyle.fromStorageId(it.storageId)) }
        AppThemeMode.entries.forEach { assertEquals(it, AppThemeMode.fromStorageId(it.storageId)) }
        AppThemePalette.entries.forEach { assertEquals(it, AppThemePalette.fromStorageId(it.storageId)) }
    }

    @Test
    fun curatedPalettesExposeOpaqueThreeColorSwatches() {
        assertTrue(AppThemePalette.entries.size >= 10)
        AppThemePalette.entries.forEach { palette ->
            assertEquals(3, palette.swatches.size)
            assertTrue(palette.swatches.all { color -> color ushr 24 == 0xFFL })
        }
    }
}
