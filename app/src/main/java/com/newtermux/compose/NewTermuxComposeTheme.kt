package com.newtermux.compose

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.newtermux.features.NewTermuxTheme

/**
 * Shared Jetpack Compose theme for the migrated NewTermux screens.
 *
 * The app is a dark, terminal-first experience, so this is a fixed dark
 * Material 3 scheme whose primary/secondary accent is pulled live from the
 * user's chosen accent color ([NewTermuxTheme.getAccentColor]) — keeping the
 * Compose screens visually consistent with the rest of the (View-based) UI.
 */
@Composable
fun NewTermuxComposeTheme(
    context: Context,
    content: @Composable () -> Unit,
) {
    val accent = Color(
        if (NewTermuxAccent.color != 0) NewTermuxAccent.color
        else NewTermuxTheme.getAccentColor(context)
    )
    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        primaryContainer = accent.copy(alpha = 0.24f),
        onPrimaryContainer = Color(0xFFECECEC),
        secondary = accent,
        onSecondary = Color.Black,
        background = Color(0xFF121212),
        onBackground = Color(0xFFECECEC),
        surface = Color(0xFF1E1E1E),
        onSurface = Color(0xFFECECEC),
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFB6B6B6),
        outline = Color(0xFF444444),
        error = Color(0xFFCF6679),
        onError = Color.Black,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
