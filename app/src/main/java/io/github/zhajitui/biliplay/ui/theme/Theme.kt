package io.github.zhajitui.biliplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BiliPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2230),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = BiliPinkMuted,
    onSecondary = Color.White,
    background = Ink,
    onBackground = InkText,
    surface = InkElevated,
    onSurface = InkText,
    surfaceVariant = InkElevatedHigh,
    onSurfaceVariant = InkTextMuted,
    outline = InkOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = BiliPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3EA),
    onPrimaryContainer = Color(0xFF3A0A18),
    secondary = BiliPinkMuted,
    onSecondary = Color.White,
    background = Paper,
    onBackground = PaperText,
    surface = PaperElevated,
    onSurface = PaperText,
    surfaceVariant = PaperElevatedHigh,
    onSurfaceVariant = PaperTextMuted,
    outline = PaperOutline,
)

@Composable
fun BiliPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = BiliTypography,
        content = content,
    )
}
