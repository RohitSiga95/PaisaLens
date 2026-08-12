package com.paisalens.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.AppThemeMode
import com.paisalens.app.data.model.AppThemePalette
import com.paisalens.app.data.model.AppThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeColorSchemeTest {
    @Test
    fun everyCuratedThemeKeepsMaterialTextPairsReadable() {
        AppThemeStyle.entries.forEach { style ->
            AppThemePalette.entries.forEach { palette ->
                AppThemeMode.entries.forEach { mode ->
                    listOf(false, true).forEach { systemDark ->
                        val configuration = AppThemeConfiguration(style, mode, palette)
                        val scheme = colorSchemeFor(
                            configuration = configuration,
                            darkTheme = configuration.isDark(systemDark),
                        )

                        semanticPairs(scheme).forEach { (name, foreground, background) ->
                            val ratio = contrastRatio(foreground, background)
                            assertTrue(
                                "$style/$mode/$palette $name contrast was $ratio",
                                ratio >= MIN_TEXT_CONTRAST,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun amoledUsesTrueBlackForEveryNeutralCanvasAndSurfaceRole() {
        val violations = mutableListOf<String>()
        AppThemePalette.entries.forEach { palette ->
            val configuration = AppThemeConfiguration(
                style = AppThemeStyle.AMOLED,
                mode = AppThemeMode.LIGHT,
                palette = palette,
            )
            val scheme = colorSchemeFor(configuration, darkTheme = true)

            amoledNeutralRoles(scheme).forEach { (name, color) ->
                if (color != Color.Black) {
                    violations += "AMOLED/$palette $name was $color"
                }
            }
        }

        assertEquals(
            "Every AMOLED neutral canvas/surface must switch OLED pixels fully off",
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun onboardingLogoForegroundIsReadableAgainstBothGradientStopsForEveryTheme() {
        val violations = mutableListOf<String>()
        AppThemeStyle.entries.forEach { style ->
            AppThemePalette.entries.forEach { palette ->
                AppThemeMode.entries.forEach { mode ->
                    listOf(false, true).forEach { systemDark ->
                        val configuration = AppThemeConfiguration(style, mode, palette)
                        val scheme = colorSchemeFor(
                            configuration = configuration,
                            darkTheme = configuration.isDark(systemDark),
                        )

                        onboardingLogoStops(scheme).forEach { (name, stop) ->
                            val ratio = contrastRatio(scheme.onBackground, stop)
                            if (ratio < MIN_TEXT_CONTRAST) {
                                violations += "$style/$mode/$palette/systemDark=$systemDark $name=$ratio"
                            }
                        }
                    }
                }
            }
        }

        assertEquals(
            "The onboarding logo foreground must remain readable against both gradient stops",
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun gradientCanvasAndHeroStopsStayReadable() {
        AppThemePalette.entries.forEach { palette ->
            listOf(false, true).forEach { isDark ->
                val configuration = AppThemeConfiguration(
                    style = AppThemeStyle.GRADIENT,
                    mode = if (isDark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                    palette = palette,
                )
                val scheme = colorSchemeFor(configuration, isDark)

                gradientBackgroundColorsFor(palette, isDark).forEachIndexed { index, stop ->
                    assertTrue(
                        "$palette gradient stop $index was not readable",
                        contrastRatio(scheme.onBackground, stop) >= MIN_TEXT_CONTRAST,
                    )
                }

                listOf(
                    scheme.primaryContainer,
                    scheme.tertiaryContainer,
                    scheme.secondaryContainer,
                ).forEachIndexed { index, stop ->
                    assertTrue(
                        "$palette hero stop $index was not readable",
                        contrastRatio(scheme.onPrimaryContainer, stop) >= MIN_TEXT_CONTRAST,
                    )
                }
            }
        }
    }

    private fun semanticPairs(scheme: ColorScheme) = listOf(
        Triple("primary", scheme.onPrimary, scheme.primary),
        Triple("primary container", scheme.onPrimaryContainer, scheme.primaryContainer),
        Triple("secondary", scheme.onSecondary, scheme.secondary),
        Triple("secondary container", scheme.onSecondaryContainer, scheme.secondaryContainer),
        Triple("tertiary", scheme.onTertiary, scheme.tertiary),
        Triple("tertiary container", scheme.onTertiaryContainer, scheme.tertiaryContainer),
        Triple("background", scheme.onBackground, scheme.background),
        Triple("surface", scheme.onSurface, scheme.surface),
        Triple("surface variant", scheme.onSurfaceVariant, scheme.surfaceVariant),
        Triple("error", scheme.onError, scheme.error),
        Triple("error container", scheme.onErrorContainer, scheme.errorContainer),
    )

    private fun amoledNeutralRoles(scheme: ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surface variant" to scheme.surfaceVariant,
        "surface dim" to scheme.surfaceDim,
        "surface bright" to scheme.surfaceBright,
        "surface container lowest" to scheme.surfaceContainerLowest,
        "surface container low" to scheme.surfaceContainerLow,
        "surface container" to scheme.surfaceContainer,
        "surface container high" to scheme.surfaceContainerHigh,
        "surface container highest" to scheme.surfaceContainerHighest,
    )

    /** Mirrors the two semantic container colors used by OnboardingScreen.LogoMark. */
    private fun onboardingLogoStops(scheme: ColorScheme) = listOf(
        "primary container stop" to scheme.primaryContainer,
        "secondary container stop" to scheme.secondaryContainer,
    )

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5f
    }
}
