package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = VoicePurpleLight,
    onPrimary = Color(0xFF390B63),
    primaryContainer = VoicePurple,
    onPrimaryContainer = Color(0xFFF3E8FF),
    secondary = LavenderMist,
    onSecondary = SlateInk,
    background = SlateSurfaceDark,
    onBackground = SlateTextDark,
    surface = SlateSurfaceDark,
    onSurface = SlateTextDark,
    surfaceVariant = SlateCardDark,
    onSurfaceVariant = Color(0xFFCCC5D6),
)

private val LightColorScheme = lightColorScheme(
    primary = VoicePurple,
    onPrimary = Color.White,
    primaryContainer = VoicePurpleContainer,
    onPrimaryContainer = VoiceOnPurpleContainer,
    secondary = LavenderMist,
    onSecondary = SlateInk,
    background = SlateSurfaceLight,
    onBackground = SlateInk,
    surface = SlateSurfaceLight,
    onSurface = SlateInk,
    surfaceVariant = SlateCardLight,
    onSurfaceVariant = Color(0xFF49454F),
)

@Composable
fun VoiceFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
