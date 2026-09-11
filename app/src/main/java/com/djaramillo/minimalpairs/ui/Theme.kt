package com.djaramillo.minimalpairs.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed palette (dynamic colour off) that reads well in light and dark:
 * a deep blue primary (matches the launcher background), a warm accent for
 * the highlighted phoneme, and clear success / error tones.
 */
private val Blue = Color(0xFF1F4E79)
private val BlueLight = Color(0xFF9DC3EA)
private val Amber = Color(0xFFB5541C)
private val AmberLight = Color(0xFFFFB68A)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF0B2C4E),
    secondary = Color(0xFF4F6072),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E4F2),
    onSecondaryContainer = Color(0xFF17283A),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBC9),
    onTertiaryContainer = Color(0xFF3C1400),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
)

private val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF15406A),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFB8C8DA),
    onSecondary = Color(0xFF223140),
    secondaryContainer = Color(0xFF394857),
    onSecondaryContainer = Color(0xFFD5E4F6),
    tertiary = AmberLight,
    onTertiary = Color(0xFF552100),
    tertiaryContainer = Color(0xFF783300),
    onTertiaryContainer = Color(0xFFFFDBC9),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
)

/** Success tone for "Correct" (not part of the M3 scheme). */
@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7FD69A) else Color(0xFF1B7F3B)

private val AppTypography = Typography(
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
)

@Composable
fun MinimalPairsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
