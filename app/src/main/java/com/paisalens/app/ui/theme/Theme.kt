package com.paisalens.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Ink = Color(0xFF07111F)
val DeepSurface = Color(0xFF0E1A2C)
val ElevatedSurface = Color(0xFF17243A)
val ElectricIndigo = Color(0xFF7784FF)
val Mint = Color(0xFF21D19F)
val WarmWhite = Color(0xFFF5F7FF)
val MutedBlue = Color(0xFFAAB6CC)
val Danger = Color(0xFFFF6B7A)

private val DarkColors = darkColorScheme(
    primary = ElectricIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF28366F),
    onPrimaryContainer = Color(0xFFE2E5FF),
    secondary = Mint,
    onSecondary = Color(0xFF002118),
    secondaryContainer = Color(0xFF0A4E3C),
    onSecondaryContainer = Color(0xFF9AF6D5),
    background = Ink,
    onBackground = WarmWhite,
    surface = DeepSurface,
    onSurface = WarmWhite,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = MutedBlue,
    error = Danger,
    outline = Color(0xFF58677E),
    outlineVariant = Color(0xFF26354B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4153D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF14226F),
    secondary = Color(0xFF007A5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F3DD),
    onSecondaryContainer = Color(0xFF00382A),
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF101827),
    surface = Color.White,
    onSurface = Color(0xFF101827),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF4A5568),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFF737A8C),
    outlineVariant = Color(0xFFD6DBE6),
)

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
    extraSmall = RoundedCornerShape(10),
    small = RoundedCornerShape(14),
    medium = RoundedCornerShape(20),
    large = RoundedCornerShape(28),
    extraLarge = RoundedCornerShape(36),
)

@Composable
fun PaisaLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
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
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PaisaTypography,
        shapes = PaisaShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("PaisaLens must be hosted in an Activity")
}
