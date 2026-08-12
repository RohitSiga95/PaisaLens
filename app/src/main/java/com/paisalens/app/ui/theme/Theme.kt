package com.paisalens.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.AppThemePalette
import com.paisalens.app.data.model.AppThemeStyle

val Ink = Color(0xFF07111F)
val DeepSurface = Color(0xFF0E1A2C)
val ElevatedSurface = Color(0xFF17243A)
val ElectricIndigo = Color(0xFF7784FF)
val Mint = Color(0xFF21D19F)
val WarmWhite = Color(0xFFF5F7FF)
val MutedBlue = Color(0xFFAAB6CC)
val Danger = Color(0xFFFF6B7A)

private val PaisaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val PaisaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun PaisaLensTheme(
    configuration: AppThemeConfiguration = AppThemeConfiguration(),
    content: @Composable () -> Unit,
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = configuration.isDark(systemInDarkTheme)
    val colors = colorSchemeFor(configuration, darkTheme)
    val background = backgroundBrushFor(configuration, darkTheme, colors)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = PaisaTypography,
        shapes = PaisaShapes,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background),
        ) {
            content()
        }
    }
}

internal fun colorSchemeFor(
    configuration: AppThemeConfiguration,
    darkTheme: Boolean,
): ColorScheme {
    val palette = configuration.palette
    val primarySeed = Color(palette.primaryArgb)
    val secondarySeed = Color(palette.secondaryArgb)
    val tertiarySeed = Color(palette.tertiaryArgb)

    return when {
        configuration.style == AppThemeStyle.AMOLED -> amoledColorScheme(
            primarySeed = primarySeed,
            secondarySeed = secondarySeed,
            tertiarySeed = tertiarySeed,
        )
        darkTheme -> darkPaletteColorScheme(primarySeed, secondarySeed, tertiarySeed)
        else -> lightPaletteColorScheme(primarySeed, secondarySeed, tertiarySeed)
    }
}

private fun darkPaletteColorScheme(
    primarySeed: Color,
    secondarySeed: Color,
    tertiarySeed: Color,
): ColorScheme {
    val primary = lerp(primarySeed, Color.White, 0.14f)
    val secondary = lerp(secondarySeed, Color.White, 0.13f)
    val tertiary = lerp(tertiarySeed, Color.White, 0.08f)
    return darkColorScheme(
        primary = primary,
        onPrimary = readableForeground(primary),
        primaryContainer = lerp(primarySeed, Color.Black, 0.63f),
        onPrimaryContainer = lerp(primarySeed, Color.White, 0.77f),
        secondary = secondary,
        onSecondary = readableForeground(secondary),
        secondaryContainer = lerp(secondarySeed, Color.Black, 0.67f),
        onSecondaryContainer = lerp(secondarySeed, Color.White, 0.79f),
        tertiary = tertiary,
        onTertiary = readableForeground(tertiary),
        tertiaryContainer = lerp(tertiarySeed, Color.Black, 0.68f),
        onTertiaryContainer = lerp(tertiarySeed, Color.White, 0.80f),
        background = lerp(primarySeed, Color(0xFF050B14), 0.93f),
        onBackground = WarmWhite,
        surface = lerp(primarySeed, Color(0xFF0B1422), 0.94f),
        onSurface = WarmWhite,
        surfaceVariant = lerp(primarySeed, Color(0xFF162235), 0.91f),
        onSurfaceVariant = Color(0xFFB6C1D4),
        surfaceTint = primary,
        error = Danger,
        onError = Color(0xFF31000A),
        errorContainer = Color(0xFF5B1621),
        onErrorContainer = Color(0xFFFFD9DE),
        outline = Color(0xFF68768B),
        outlineVariant = Color(0xFF2A384C),
    )
}

private fun lightPaletteColorScheme(
    primarySeed: Color,
    secondarySeed: Color,
    tertiarySeed: Color,
): ColorScheme {
    val primary = lerp(primarySeed, Color.Black, 0.34f)
    val secondary = lerp(secondarySeed, Color.Black, 0.45f)
    val tertiary = lerp(tertiarySeed, Color.Black, 0.50f)
    return lightColorScheme(
        primary = primary,
        onPrimary = readableForeground(primary),
        primaryContainer = lerp(primarySeed, Color.White, 0.82f),
        onPrimaryContainer = lerp(primarySeed, Color.Black, 0.59f),
        secondary = secondary,
        onSecondary = readableForeground(secondary),
        secondaryContainer = lerp(secondarySeed, Color.White, 0.82f),
        onSecondaryContainer = lerp(secondarySeed, Color.Black, 0.62f),
        tertiary = tertiary,
        onTertiary = readableForeground(tertiary),
        tertiaryContainer = lerp(tertiarySeed, Color.White, 0.83f),
        onTertiaryContainer = lerp(tertiarySeed, Color.Black, 0.61f),
        background = lerp(primarySeed, Color(0xFFF7F8FC), 0.96f),
        onBackground = Color(0xFF111827),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF111827),
        surfaceVariant = lerp(primarySeed, Color(0xFFE9EDF5), 0.94f),
        onSurfaceVariant = Color(0xFF475569),
        surfaceTint = primary,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF6B7280),
        outlineVariant = Color(0xFFD3D9E4),
    )
}

private fun amoledColorScheme(
    primarySeed: Color,
    secondarySeed: Color,
    tertiarySeed: Color,
): ColorScheme = darkColorScheme(
    primary = lerp(primarySeed, Color.White, 0.16f),
    onPrimary = readableForeground(lerp(primarySeed, Color.White, 0.16f)),
    primaryContainer = lerp(primarySeed, Color.Black, 0.69f),
    onPrimaryContainer = lerp(primarySeed, Color.White, 0.80f),
    secondary = lerp(secondarySeed, Color.White, 0.13f),
    onSecondary = readableForeground(lerp(secondarySeed, Color.White, 0.13f)),
    secondaryContainer = lerp(secondarySeed, Color.Black, 0.73f),
    onSecondaryContainer = lerp(secondarySeed, Color.White, 0.82f),
    tertiary = lerp(tertiarySeed, Color.White, 0.10f),
    onTertiary = readableForeground(lerp(tertiarySeed, Color.White, 0.10f)),
    tertiaryContainer = lerp(tertiarySeed, Color.Black, 0.74f),
    onTertiaryContainer = lerp(tertiarySeed, Color.White, 0.82f),
    background = Color.Black,
    onBackground = Color(0xFFF7F7FA),
    surface = Color.Black,
    onSurface = Color(0xFFF7F7FA),
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color(0xFFC4C4CC),
    surfaceTint = Color.Transparent,
    error = Color(0xFFFF6B7A),
    onError = Color(0xFF310008),
    errorContainer = Color(0xFF5B1520),
    onErrorContainer = Color(0xFFFFD9DD),
    outline = Color(0xFF77777F),
    outlineVariant = Color(0xFF29292E),
).copy(
    surfaceBright = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainerLowest = Color.Black,
)

private fun backgroundBrushFor(
    configuration: AppThemeConfiguration,
    darkTheme: Boolean,
    colors: ColorScheme,
): Brush {
    if (configuration.style != AppThemeStyle.GRADIENT) return SolidColor(colors.background)
    return Brush.linearGradient(
        colors = gradientBackgroundColorsFor(configuration.palette, darkTheme),
        start = Offset.Zero,
        end = Offset(1_200f, 1_800f),
    )
}

internal fun gradientBackgroundColorsFor(
    palette: AppThemePalette,
    darkTheme: Boolean,
): List<Color> {
    val primary = Color(palette.primaryArgb)
    val secondary = Color(palette.secondaryArgb)
    val tertiary = Color(palette.tertiaryArgb)
    return if (darkTheme) {
        listOf(
            lerp(primary, Color(0xFF02050B), 0.76f),
            lerp(tertiary, Color(0xFF050912), 0.88f),
            lerp(secondary, Color(0xFF02070A), 0.80f),
        )
    } else {
        listOf(
            lerp(primary, Color.White, 0.87f),
            lerp(tertiary, Color(0xFFF8FAFF), 0.92f),
            lerp(secondary, Color.White, 0.89f),
        )
    }
}

private fun readableForeground(background: Color): Color {
    val whiteContrast = (Color.White.luminance() + 0.05f) / (background.luminance() + 0.05f)
    return if (whiteContrast >= 4.5f) Color.White else Color.Black
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("PaisaLens must be hosted in an Activity")
}
