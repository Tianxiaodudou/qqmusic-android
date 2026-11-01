package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 兼容用常量：新代码请用 AppTheme.colors.xxx，这些只是 DarkColors 的别名。 */
val Brand = DarkColors.brand
val BrandDim = DarkColors.brandDim
val Bg = DarkColors.bg
val Surface1 = DarkColors.surface1
val Surface2 = DarkColors.surface3
val TextMain = DarkColors.textPrimary
val TextSub = DarkColors.textSecondary

private val DarkScheme = darkColorScheme(
    primary = DarkColors.brand,
    onPrimary = DarkColors.textOnBrand,
    secondary = DarkColors.brandDim,
    onSecondary = DarkColors.textPrimary,
    background = DarkColors.bg,
    onBackground = DarkColors.textPrimary,
    surface = DarkColors.surface1,
    onSurface = DarkColors.textPrimary,
    surfaceVariant = DarkColors.surface3,
    onSurfaceVariant = DarkColors.textSecondary,
    outline = DarkColors.lineStrong,
    error = DarkColors.error,
    onError = Color.White,
)

@Composable
fun QQMusicTheme(content: @Composable () -> Unit) {
    AppThemeProvider {
        MaterialTheme(colorScheme = DarkScheme, content = content)
    }
}
