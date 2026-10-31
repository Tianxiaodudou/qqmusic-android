package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 本地设置：音质、播放速度、歌词全局样式、单曲歌词偏移、均衡器预设、缓存清理。
 * 全部落在 SharedPreferences，进程重启后仍然生效。
 */
object Settings {

    enum class Quality(val label: String, val detail: String) {
        STANDARD("标准", "128kbps · MP3"),
        HIGH("高品质", "320kbps · MP3"),
        LOSSLESS("无损", "FLAC · 需会员"),
    }

    /** 歌词样式（偏好类，全局生效；与「歌词同步」的单曲时间偏移是两回事）。 */
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
    private const val K_EQ = "eq_preset"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun quality(context: Context): Quality = when (prefs(context).getString(K_QUALITY, "lossless")) {
        "standard" -> Quality.STANDARD
        "high" -> Quality.HIGH
        "auto", "lossless" -> Quality.LOSSLESS
        else -> Quality.LOSSLESS
    }

    fun setQuality(context: Context, q: Quality) {
        val raw = when (q) {
            Quality.STANDARD -> "standard"
            Quality.HIGH -> "high"
            Quality.LOSSLESS -> "lossless"
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

    /** 0 = 关闭，其余见 [AudioFx.PRESETS]。 */
    fun eqPreset(context: Context): Int = prefs(context).getInt(K_EQ, 0)

    fun setEqPreset(context: Context, index: Int) {
        prefs(context).edit().putInt(K_EQ, index).apply()
    }

    /** 清掉无损解密缓存与封面临时文件，返回释放的字节数。 */
    fun clearCache(context: Context): Long {
        var freed = 0L
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
