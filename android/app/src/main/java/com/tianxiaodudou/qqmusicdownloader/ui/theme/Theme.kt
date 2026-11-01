package com.tianxiaodudou.qqmusicdownloader.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tianxiaodudou.qqmusicdownloader.net.Settings

/** 兼容用常量：新代码请用 AppTheme.colors.xxx，这些只是深色实例的别名。 */
val Brand = DarkColors.brand
val BrandDim = DarkColors.brandDim
val Bg = DarkColors.bg
val Surface1 = DarkColors.surface1
val Surface2 = DarkColors.surface3
val TextMain = DarkColors.textPrimary
val TextSub = DarkColors.textSecondary

/**
 * 主题模式：跟随系统 / 强制日间 / 强制夜间。
 * 非 Composable 代码（比如桌面歌词悬浮窗）通过 [ThemeStore.colors] 取当前生效的一套颜色。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("日间"),
    DARK("夜间"),
}

object ThemeStore {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var systemDark by mutableStateOf(true)
        private set

    val dark: Boolean
        get() = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    val colors: AppColors get() = if (dark) DarkColors else LightColors

    fun init(ctx: Context) {
        mode = Settings.themeMode(ctx)
    }

    fun set(ctx: Context, m: ThemeMode) {
        mode = m
        Settings.setThemeMode(ctx, m)
    }

    fun updateSystem(isDark: Boolean) {
        systemDark = isDark
    }
}

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

private val LightScheme = lightColorScheme(
    primary = LightColors.brand,
    onPrimary = LightColors.textOnBrand,
    secondary = LightColors.brandDim,
    onSecondary = LightColors.textPrimary,
    background = LightColors.bg,
    onBackground = LightColors.textPrimary,
    surface = LightColors.surface1,
    onSurface = LightColors.textPrimary,
    surfaceVariant = LightColors.surface3,
    onSurfaceVariant = LightColors.textSecondary,
    outline = LightColors.lineStrong,
    error = LightColors.error,
    onError = Color.White,
)

@Composable
fun QQMusicTheme(content: @Composable () -> Unit) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect { ThemeStore.updateSystem(systemDark) }

    val dark = ThemeStore.dark
    val skin = if (dark) AppSkin(colors = DarkColors) else AppSkin(colors = LightColors)
    val scheme = if (dark) DarkScheme else LightScheme

    // 状态栏/导航栏图标跟随当前主题，浅色背景用深色图标
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    AppThemeProvider(skin) {
        // 关键：Material3 不会自己提供 LocalContentColor，没有 Surface 包裹时默认是黑色，
        // 深色皮肤下就会「黑字黑底」。这里显式给一份主题正文色。
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides skin.colors.textPrimary,
        ) {
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}
