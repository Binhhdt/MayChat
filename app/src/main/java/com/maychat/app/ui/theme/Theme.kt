package com.maychat.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Brand colors of MayChat.
private val Teal = Color(0xFF0B6E6E)
private val Amber = Color(0xFFF2A93B)

// Light mode: soft green-grey ground, white cards, teal as the main color.
private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBE8),
    onPrimaryContainer = Color(0xFF05403F),
    secondary = Amber,
    onSecondary = Color(0xFF2B1B00),
    secondaryContainer = Color(0xFFE1F1EF),
    onSecondaryContainer = Teal,
    background = Color(0xFFF4F8F7),
    onBackground = Color(0xFF10201F),
    surface = Color.White,
    onSurface = Color(0xFF10201F),
    surfaceVariant = Color(0xFFE6EEEC),
    onSurfaceVariant = Color(0xFF51605F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FBFA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE6EEEC),
    outline = Color(0xFF7C8C8A),
    outlineVariant = Color(0xFFDCE7E5),
    error = Color(0xFFC8372D),
    onError = Color.White,
)

// Dark mode: deep green-black ground, slightly lighter cards, and softer
// versions of the brand colors so text stays easy to read at night.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8C2),
    onPrimary = Color(0xFF00302E),
    primaryContainer = Color(0xFF0E4B4A),
    onPrimaryContainer = Color(0xFFBEEBE7),
    secondary = Color(0xFFF2B659),
    onSecondary = Color(0xFF2B1B00),
    secondaryContainer = Color(0xFF1F3F3D),
    onSecondaryContainer = Color(0xFFBEEBE7),
    background = Color(0xFF0F1716),
    onBackground = Color(0xFFE3EDEB),
    surface = Color(0xFF172220),
    onSurface = Color(0xFFE3EDEB),
    surfaceVariant = Color(0xFF24302E),
    onSurfaceVariant = Color(0xFFA9BAB7),
    surfaceContainerLowest = Color(0xFF0B1211),
    surfaceContainerLow = Color(0xFF131C1B),
    surfaceContainer = Color(0xFF172220),
    surfaceContainerHigh = Color(0xFF1D2927),
    surfaceContainerHighest = Color(0xFF24302E),
    outline = Color(0xFF6E807D),
    outlineVariant = Color(0xFF33423F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

// The four weights of "Be Vietnam Pro" the app uses. The font files are
// fetched when the APK is built (see app/build.gradle.kts) and packed into
// the app. If they are missing, the phone's own font is used instead.
private val fontFiles = listOf(
    "BeVietnamPro-Regular.ttf" to FontWeight.Normal,
    "BeVietnamPro-Medium.ttf" to FontWeight.Medium,
    "BeVietnamPro-SemiBold.ttf" to FontWeight.SemiBold,
    "BeVietnamPro-Bold.ttf" to FontWeight.Bold,
)

private fun loadAppFont(context: Context): FontFamily? =
    try {
        val packed = context.assets.list("fonts")?.toSet() ?: emptySet()
        if (fontFiles.all { it.first in packed }) {
            FontFamily(
                fontFiles.map { (file, weight) ->
                    Font(path = "fonts/$file", assetManager = context.assets, weight = weight)
                },
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

// All text styles of the app, in the given font.
private fun typographyWith(font: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = font),
        displayMedium = base.displayMedium.copy(fontFamily = font),
        displaySmall = base.displaySmall.copy(fontFamily = font),
        headlineLarge = base.headlineLarge.copy(fontFamily = font),
        headlineMedium = base.headlineMedium.copy(fontFamily = font),
        headlineSmall = base.headlineSmall.copy(fontFamily = font),
        titleLarge = base.titleLarge.copy(fontFamily = font),
        titleMedium = base.titleMedium.copy(fontFamily = font),
        titleSmall = base.titleSmall.copy(fontFamily = font),
        bodyLarge = base.bodyLarge.copy(fontFamily = font),
        bodyMedium = base.bodyMedium.copy(fontFamily = font),
        bodySmall = base.bodySmall.copy(fontFamily = font),
        labelLarge = base.labelLarge.copy(fontFamily = font),
        labelMedium = base.labelMedium.copy(fontFamily = font),
        labelSmall = base.labelSmall.copy(fontFamily = font),
    )
}

// Wrap every screen in this so colors and text styles are consistent.
@Composable
fun MayChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val typography = remember {
        loadAppFont(context.applicationContext)?.let { typographyWith(it) } ?: Typography()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = typography,
        content = content,
    )
}
