package com.onyxvault.messenger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── Light Color Scheme (Standard) ───────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary              = OnyxGreen,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFB7F0D6),
    onPrimaryContainer   = Color(0xFF002117),
    secondary            = OnyxBlue,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFDCEEFF),
    onSecondaryContainer = Color(0xFF001C38),
    background           = LightBackground,
    onBackground         = LightOnBackground,
    surface              = LightSurface,
    onSurface            = LightOnSurface,
    surfaceVariant       = LightSurfaceVariant,
    onSurfaceVariant     = Color(0xFF4A5568),
    error                = ErrorRed,
    onError              = Color.White,
    outline              = Color(0xFFCBD5E0),
    outlineVariant       = Color(0xFFE2E8F0)
)

// ─── Dark Color Scheme (System Dark Mode) ────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary              = Color(0xFF50C8A0),
    onPrimary            = Color(0xFF002117),
    primaryContainer     = Color(0xFF004D31),
    onPrimaryContainer   = Color(0xFFB7F0D6),
    secondary            = Color(0xFF90CAF9),
    onSecondary          = Color(0xFF001C38),
    background           = DarkBackground,
    onBackground         = Color(0xFFE2E8F0),
    surface              = DarkSurface,
    onSurface            = Color(0xFFE2E8F0),
    surfaceVariant       = DarkSurfaceVariant,
    onSurfaceVariant     = Color(0xFFB0BEC5),
    error                = Color(0xFFCF6679),
    onError              = Color.Black,
    outline              = Color(0xFF37474F)
)

@Composable
fun OnyxVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            
            // Unterdrücke Deprecation Warnung für statusBarColor, da enableEdgeToEdge() 
            // in MainActivity bereits aktiv ist und dies der Fallback-Weg ist.
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.surface.toArgb()
            
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
