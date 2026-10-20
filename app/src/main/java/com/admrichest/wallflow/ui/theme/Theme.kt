package com.admrichest.wallflow.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB8FF),
    onPrimary = Color(0xFF002F67),
    primaryContainer = Color(0xFF1C4A8C),
    secondary = Color(0xFFFFD166),
    background = Color(0xFF0E1116),
    surface = Color(0xFF151A21),
    surfaceVariant = Color(0xFF1F2630),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D5FB8),
    secondary = Color(0xFF9A6B00),
)

@Composable
fun WallFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // One UI exposes Material You colours, so follow the device palette
        // when it is available and fall back to our own otherwise.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
