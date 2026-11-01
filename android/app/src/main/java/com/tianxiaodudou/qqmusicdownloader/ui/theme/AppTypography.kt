package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle

/**
 * 字体定义层：页面写 AppTheme.typography.title，不再写 fontSize = 15.sp。
 */
@Immutable
data class AppTypography(
    val pageTitle: TextStyle,
    val sectionTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val badge: TextStyle,
    val button: TextStyle,
    val lyricLarge: TextStyle,
    val lyric: TextStyle,
    val lyricSmall: TextStyle,
)

val DefaultTypography = AppTypography(
    pageTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    sectionTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    body = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    caption = TextStyle(fontSize = 11.sp),
    badge = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium),
    button = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    lyricLarge = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
    lyric = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    lyricSmall = TextStyle(fontSize = 15.sp),
)
