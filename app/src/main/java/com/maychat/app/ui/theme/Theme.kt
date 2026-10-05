package com.maychat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors of MayChat.
private val Teal = Color(0xFF0B6E6E)
private val TealLight = Color(0xFF7FD6D2)
private val Amber = Color(0xFFF2A93B)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Amber,
    onSecondary = Color(0xFF2B1B00),
    background = Color(0xFFF6FAF9),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003736),
    secondary = Amber,
    onSecondary = Color(0xFF2B1B00),
    background = Color(0xFF0E1514),
    surface = Color(0xFF16201F),
)

// Wrap every screen in this so colors and text styles are consistent.
@Composable
fun MayChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
