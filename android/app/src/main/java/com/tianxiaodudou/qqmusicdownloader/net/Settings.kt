package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 本地设置：音质、播放速度、歌词全局样式、单曲歌词偏移、均衡器预设、缓存清理。
 * 全部落在 SharedPreferences，进程重启后仍然生效。
 */
object Settings {

    enum class Quality(
        val label: String,
        val detail: String,
        /** 普通直链的码率代码；空表示只能走加密链接。 */
        val code: String,
        /** EVkey 加密链接的代码（mflac/mgg/mmp4）。 */
        val encryptedCode: String = "",
        val encryptedExt: String = "",
        val outputExt: String = "",
    ) {
        STANDARD("标准", "128kbps · MP3", "M500"),
        HIGH("高品质", "320kbps · MP3", "M800"),
        LOSSLESS("SQ 无损", "FLAC · 需会员", "F000", "F0M0", ".mflac", "flac"),
        HIRES("Hi-Res", "OGG 640 · 需会员", "O801", "O8M1", ".mgg", "ogg"),
        MASTER("臻品母带", "FLAC · 需超级会员", "AI00", "AIM0", ".mflac", "flac"),
        DOLBY("杜比全景声", "MP4 · 需超级会员", "D004", "D0M4", ".mmp4", "mp4"),
    }

    /** 歌词样式（偏好类，全局生效；与「歌词同步」的单曲时间偏移是两回事）。 */
    /** 付费单曲 / 数字专辑在列表里的处理方式。 */
    enum class PaidPolicy(val label: String) {
        GREY("置灰不可选"),
        HIDE("直接隐藏"),
    }

    data class LyricStyle(
        val scale: Float = 1f,
        val center: Boolean = true,
        val showTrans: Boolean = true,
        val showRoma: Boolean = false,
    )

    private const val PREFS = "settings"
    private const val K_QUALITY = "quality"
    private const val K_SPEED = "speed"
    private const val K_OFFSETS = "lyric_offsets"
    private const val K_SCALE = "style_scale"
    private const val K_CENTER = "style_center"
    private const val K_TRANS = "style_trans"
    private const val K_ROMA = "style_roma"
    private const val K_PAID = "paid_policy"
    private const val K_AUTO_DOWNGRADE = "auto_downgrade"
    private const val K_DESKTOP_LYRICS = "desktop_lyrics"
    private const val K_BT_LYRICS = "bt_lyrics"
    private const val K_THEME = "theme_mode"

    /** 首页/搜索的每页条数：固定 15，向下滚动继续加载。 */
    const val PAGE_SIZE = 15

    /** 首页模块：key、标题。显隐可配置，条数固定。 */
    data class HomeModule(val key: String, val title: String)

    val HOME_MODULES = listOf(
        HomeModule("feed", "为你推荐"),
        HomeModule("songlists", "推荐歌单"),
        HomeModule("newsongs", "新歌速递"),
        HomeModule("radar", "每日推荐"),
        HomeModule("albums", "新碟上架"),
        HomeModule("singers", "热门歌手"),
        HomeModule("hot", "热搜榜"),
        HomeModule("charts", "排行榜"),
        HomeModule("guess", "猜你喜欢"),
    )

    /** 主题模式：跟随系统 / 日间 / 夜间（默认跟随系统）。 */
    fun themeMode(context: Context): com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode =
        when (prefs(context).getString(K_THEME, "system")) {
            "light" -> com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode.LIGHT
            "dark" -> com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode.DARK
            else -> com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode.SYSTEM
        }

    fun setThemeMode(context: Context, mode: com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode) {
        val v = when (mode) {
            com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode.LIGHT -> "light"
            com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode.DARK -> "dark"
            else -> "system"
        }
        prefs(context).edit().putString(K_THEME, v).apply()
    }

    /** 蓝牙歌词：把当前歌词行写进 MediaMetadata 的 Title（非标准协议，默认关闭）。 */
    fun btLyrics(context: Context): Boolean = prefs(context).getBoolean(K_BT_LYRICS, false)

    fun setBtLyrics(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_BT_LYRICS, on).apply()
    }

    /** 桌面歌词悬浮窗开关（默认关闭）。 */
    fun desktopLyrics(context: Context): Boolean =
        prefs(context).getBoolean(K_DESKTOP_LYRICS, false)

    fun setDesktopLyrics(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_DESKTOP_LYRICS, on).apply()
    }

    /** 播放缓冲卡顿时是否允许自动降一档音质（默认允许）。 */
    fun autoDowngrade(context: Context): Boolean =
        prefs(context).getBoolean(K_AUTO_DOWNGRADE, true)

    fun setAutoDowngrade(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_AUTO_DOWNGRADE, on).apply()
    }

    fun homeEnabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean("home_" + key + "_on", true)

    fun setHomeEnabled(context: Context, key: String, on: Boolean) {
        prefs(context).edit().putBoolean("home_" + key + "_on", on).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun quality(context: Context): Quality = when (prefs(context).getString(K_QUALITY, "lossless")) {
        "standard" -> Quality.STANDARD
        "high" -> Quality.HIGH
        "hires" -> Quality.HIRES
        "master" -> Quality.MASTER
        "dolby" -> Quality.DOLBY
        "auto", "lossless" -> Quality.LOSSLESS
        else -> Quality.LOSSLESS
    }

    fun setQuality(context: Context, q: Quality) {
        val raw = when (q) {
            Quality.STANDARD -> "standard"
            Quality.HIGH -> "high"
            Quality.LOSSLESS -> "lossless"
            Quality.HIRES -> "hires"
            Quality.MASTER -> "master"
            Quality.DOLBY -> "dolby"
        }
        prefs(context).edit().putString(K_QUALITY, raw).apply()
    }

    fun speed(context: Context): Float =
        prefs(context).getFloat(K_SPEED, 1f).coerceIn(0.5f, 2f)

    fun setSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(K_SPEED, v.coerceIn(0.5f, 2f)).apply()
    }

    /** 单曲歌词偏移（毫秒）。正值表示歌词整体往后挪。 */
    fun lyricOffset(context: Context, mid: String): Int {
        if (mid.isEmpty()) return 0
        val raw = prefs(context).getString(K_OFFSETS, null) ?: return 0
        return runCatching { JSONObject(raw).optInt(mid, 0) }.getOrDefault(0)
    }

    fun setLyricOffset(context: Context, mid: String, ms: Int) {
        if (mid.isEmpty()) return
        val p = prefs(context)
        val obj = runCatching { JSONObject(p.getString(K_OFFSETS, null) ?: "{}") }.getOrElse { JSONObject() }
        if (ms == 0) obj.remove(mid) else obj.put(mid, ms)
        p.edit().putString(K_OFFSETS, obj.toString()).apply()
    }

    fun offsetCount(context: Context): Int {
        val raw = prefs(context).getString(K_OFFSETS, null) ?: return 0
        return runCatching { JSONObject(raw).length() }.getOrDefault(0)
    }

    fun clearOffsets(context: Context) {
        prefs(context).edit().remove(K_OFFSETS).apply()
    }

    fun paidPolicy(context: Context): PaidPolicy =
        if (prefs(context).getString(K_PAID, "grey") == "hide") PaidPolicy.HIDE else PaidPolicy.GREY

    fun setPaidPolicy(context: Context, p: PaidPolicy) {
        prefs(context).edit().putString(K_PAID, if (p == PaidPolicy.HIDE) "hide" else "grey").apply()
    }

    fun lyricStyle(context: Context): LyricStyle {
        val p = prefs(context)
        return LyricStyle(
            scale = p.getFloat(K_SCALE, 1f).coerceIn(0.8f, 1.35f),
            center = p.getBoolean(K_CENTER, true),
            showTrans = p.getBoolean(K_TRANS, true),
            showRoma = p.getBoolean(K_ROMA, false),
        )
    }

    fun setLyricStyle(context: Context, s: LyricStyle) {
        prefs(context).edit()
            .putFloat(K_SCALE, s.scale.coerceIn(0.8f, 1.35f))
            .putBoolean(K_CENTER, s.center)
            .putBoolean(K_TRANS, s.showTrans)
            .putBoolean(K_ROMA, s.showRoma)
            .apply()
    }

    fun resetLyricStyle(context: Context) {
        prefs(context).edit()
            .remove(K_SCALE).remove(K_CENTER).remove(K_TRANS).remove(K_ROMA)
            .apply()
    }

    /** 清掉无损解密缓存与封面临时文件，返回释放的字节数。 */
    fun clearCache(context: Context): Long {
        var freed = 0L
        // 播放缓存 / 多连接音频缓存整目录清掉，再清 cacheDir 下的零散文件
        for (name in listOf("lossless", "audio_cache", "media_cache", "update")) {
            val d = File(context.cacheDir, name)
            if (!d.exists()) continue
            freed += runCatching { d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
            runCatching { d.deleteRecursively() }
        }
        val dirs = listOf(File(context.cacheDir, "lossless"), context.cacheDir)
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (dir == context.cacheDir && !f.name.endsWith(".jpg") &&
                    !f.name.endsWith(".mp3") && !f.name.endsWith(".mflac")
                ) continue
                freed += f.length()
                f.delete()
            }
        }
        return freed
    }
}
