package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context

/** 每首歌的上次播放进度，用于续播。 */
object ResumeStore {

    private const val PREFS = "resume"
    private const val MAX = 300

    fun save(context: Context, mid: String, positionMs: Long, durationMs: Long) {
        if (mid.isEmpty() || positionMs < 3000) return
        if (durationMs > 0 && positionMs > durationMs - 4000) {
            // 快播完了，下次从头开始
            clear(context, mid)
            return
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong("p_" + mid, positionMs).putLong("t_" + mid, System.currentTimeMillis()).apply()
        // 简单裁剪：超过 300 首就清掉最旧的一半，避免 prefs 无限膨胀
        if (p.all.size > MAX * 2) {
            val entries = p.all.entries.filter { it.key.startsWith("t_") }
                .sortedBy { it.value as? Long ?: 0L }
            entries.take(entries.size / 2).forEach { e ->
                val midKey = e.key.removePrefix("t_")
                p.edit().remove("t_" + midKey).remove("p_" + midKey).apply()
            }
        }
    }

    /** 只保留这一首的进度，其它曲目一律清掉（切歌回到 0 秒）。 */
    fun keepOnly(context: Context, mid: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keep = setOf("p_" + mid, "t_" + mid)
        val stale = p.all.keys.filter { it.startsWith("p_") || it.startsWith("t_") }.filter { it !in keep }
        if (stale.isEmpty()) return
        val e = p.edit()
        stale.forEach { e.remove(it) }
        e.apply()
    }

    fun get(context: Context, mid: String): Long {
        if (mid.isEmpty()) return 0
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pos = p.getLong("p_" + mid, 0L)
        val at = p.getLong("t_" + mid, 0L)
        if (pos <= 0 || at <= 0) return 0
        // 超过 30 天没听就不续了
        if (System.currentTimeMillis() - at > 30L * 24 * 3600 * 1000) return 0
        return pos
    }

    fun clear(context: Context, mid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("p_" + mid).remove("t_" + mid).apply()
    }
}
