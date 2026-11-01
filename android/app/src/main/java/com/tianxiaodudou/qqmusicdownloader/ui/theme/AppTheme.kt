package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalShapes = staticCompositionLocalOf { DefaultShapes }
private val LocalStrokes = staticCompositionLocalOf { appStrokes(DarkColors) }
private val LocalTypography = staticCompositionLocalOf { DefaultTypography }
private val LocalDimens = staticCompositionLocalOf { DefaultDimens }

/**
 * 主题入口：页面通过 AppTheme.colors / shapes / strokes / typography / dimens 取视觉值。
 * 换成皮肤只需要换这里的实例，页面代码不动。
 */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalColors.current

    val shapes: AppShapes
        @Composable @ReadOnlyComposable get() = LocalShapes.current

    val strokes: AppStrokes
        @Composable @ReadOnlyComposable get() = LocalStrokes.current

    val typography: AppTypography
        @Composable @ReadOnlyComposable get() = LocalTypography.current

    val dimens: AppDimens
        @Composable @ReadOnlyComposable get() = LocalDimens.current
}

/** 深色皮肤实例（未来新增主题只需再给一份 AppColors）。 */
data class AppSkin(
    val colors: AppColors = DarkColors,
    val shapes: AppShapes = DefaultShapes,
    val typography: AppTypography = DefaultTypography,
    val dimens: AppDimens = DefaultDimens,
) {
    val strokes: AppStrokes get() = appStrokes(colors)
}

@Composable
fun AppThemeProvider(skin: AppSkin = AppSkin(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalColors provides skin.colors,
        LocalShapes provides skin.shapes,
        LocalStrokes provides skin.strokes,
        LocalTypography provides skin.typography,
        LocalDimens provides skin.dimens,
        content = content,
    )
}
