package com.schoolapp.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColors = lightColorScheme(
    primary = Primary,
    onPrimary = Surface,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = Primary,
    secondary = Secondary,
    onSecondary = Surface,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = Secondary,
    tertiary = WarningAmber,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = DangerRed,
    onError = Surface,
    errorContainer = DangerContainer,
    onErrorContainer = DangerRed,
)

@Composable
fun SchoolAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = SchoolTypography,
        content = content,
    )
}
