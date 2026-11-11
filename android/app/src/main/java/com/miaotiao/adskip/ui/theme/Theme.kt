package com.miaotiao.adskip.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6BFF),
    onPrimary = Color.White,
    background = Color(0xFFF2F3F7),
    surface = Color.White,
    onSurface = Color(0xFF16181D),
    onSurfaceVariant = Color(0xFF6B7280)
)

@Composable
fun MiaoTiaoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
