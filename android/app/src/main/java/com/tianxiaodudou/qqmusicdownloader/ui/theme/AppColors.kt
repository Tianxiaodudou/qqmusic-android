package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 颜色定义层：页面与组件只允许引用这里的语义色，不再写 Color(0x…)。
 * 目前只有一套深色（DarkColors），结构支持后续加浅色/其他皮肤。
 */
@Immutable
data class AppColors(
    // 背景
    val bg: Color,
    val bgElevated: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val scrim: Color,
    val overlay: Color,
    // 文字
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val textOnBrand: Color,
    // 品牌
    val brand: Color,
    val brandDim: Color,
    val brandSoft: Color,
    val brandBorder: Color,
    // 状态
    val error: Color,
    val errorSoft: Color,
    val success: Color,
    val warning: Color,
    val warningSoft: Color,
    // 控件
    val switchThumb: Color,
    val switchTrack: Color,
    val progress: Color,
    val progressTrack: Color,
    val progressBuffered: Color,
    val selectedBg: Color,
    val selectedText: Color,
    val chipBg: Color,
    // 描边/分割
    val line: Color,
    val lineStrong: Color,
    val lineFocus: Color,
    val lineDisabled: Color,
    // 封面/图片占位
    val coverPlaceholder: Color,
    // 歌词
    val lyricActive: Color,
    val lyricSung: Color,
    val lyricInactive: Color,
    val lyricDim: Color,
)

val DarkColors = AppColors(
    bg = Color(0xFF0B0D10),
    bgElevated = Color(0xFF11141A),
    surface1 = Color(0xFF171B22),
    surface2 = Color(0xFF1B2029),
    surface3 = Color(0xFF222833),
    scrim = Color(0xCC000000),
    overlay = Color(0x47000000),
    textPrimary = Color(0xFFF0F3F8),
    textSecondary = Color(0xFF8C96A6),
    textTertiary = Color(0xFF5A6270),
    textDisabled = Color(0xFF3A4150),
    textOnBrand = Color(0xFF06230F),
    brand = Color(0xFF1FC365),
    brandDim = Color(0xFF178A49),
    brandSoft = Color(0xFF1B2A22),
    brandBorder = Color(0x221FC365),
    error = Color(0xFFFF6B6B),
    errorSoft = Color(0x33FF6B6B),
    success = Color(0xFF6FE7A5),
    warning = Color(0xFFFFB74D),
    warningSoft = Color(0x33FFB74D),
    switchThumb = Color(0xFF06230F),
    switchTrack = Color(0xFF1FC365),
    progress = Color(0xFF1FC365),
    progressTrack = Color(0xFF262C36),
    progressBuffered = Color(0x8CE6ECF5),
    selectedBg = Color(0xFF1B2A22),
    selectedText = Color(0xFF6FE7A5),
    chipBg = Color(0xFF232A35),
    line = Color(0xFF232A35),
    lineStrong = Color(0xFF2A3040),
    lineFocus = Color(0xFF1FC365),
    lineDisabled = Color(0xFF2A2F38),
    coverPlaceholder = Color(0xFF232A35),
    lyricActive = Color(0xFFF5F7FA),
    lyricSung = Color(0xFF6FE7A5),
    lyricInactive = Color(0xFFC9D1DE),
    lyricDim = Color(0xFF9AA3B2),
)

/** 日间（浅色）皮肤：同一套语义键，全部按浅底深字重算对比度。 */
val LightColors = AppColors(
    bg = Color(0xFFF5F7FA),
    bgElevated = Color(0xFFFFFFFF),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F3F7),
    surface3 = Color(0xFFE6EBF1),
    scrim = Color(0x66000000),
    overlay = Color(0x331A1F28),
    textPrimary = Color(0xFF16181D),
    textSecondary = Color(0xFF5B6472),
    textTertiary = Color(0xFF8A93A1),
    textDisabled = Color(0xFFB4BCC7),
    textOnBrand = Color(0xFFFFFFFF),
    brand = Color(0xFF12A150),
    brandDim = Color(0xFF0C7B3C),
    brandSoft = Color(0xFFE3F7EC),
    brandBorder = Color(0x3312A150),
    error = Color(0xFFC62F2F),
    errorSoft = Color(0x33C62F2F),
    success = Color(0xFF12894B),
    warning = Color(0xFFB26A00),
    warningSoft = Color(0x33B26A00),
    switchThumb = Color(0xFFFFFFFF),
    switchTrack = Color(0xFF12A150),
    progress = Color(0xFF12A150),
    progressTrack = Color(0xFFDDE3EA),
    progressBuffered = Color(0x8C8A93A1),
    selectedBg = Color(0xFFE3F7EC),
    selectedText = Color(0xFF0C7B3C),
    chipBg = Color(0xFFEDF1F6),
    line = Color(0xFFE2E7EE),
    lineStrong = Color(0xFFCFD6E0),
    lineFocus = Color(0xFF12A150),
    lineDisabled = Color(0xFFE8ECF2),
    coverPlaceholder = Color(0xFFE6EBF1),
    lyricActive = Color(0xFF16181D),
    lyricSung = Color(0xFF12A150),
    lyricInactive = Color(0xFF3A4250),
    lyricDim = Color(0xFF5B6472),
)

