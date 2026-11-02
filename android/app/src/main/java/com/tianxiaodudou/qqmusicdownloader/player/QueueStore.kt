package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放队列持久化：进程被杀、应用重启后恢复队列与上次的曲目/进度。
 * 只存必要的歌曲字段，歌曲对象本身不可序列化，这里手工转 JSON。
 */
object QueueStore {

    private const val PREFS = "queue_store"
    private const val K_DATA = "data"

    data class Snapshot(val songs: List<MusicApi.Song>, val index: Int, val positionMs: Long)

    fun save(context: Context, songs: List<MusicApi.Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) {
            clear(context)
            return
        }
        runCatching {
            val arr = JSONArray()
            songs.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("mid", s.mid)
                        .put("name", s.name)
                        .put("singers", s.singers)
                        .put("album", s.album)
                        .put("albumMid", s.albumMid)
                        .put("interval", s.interval)
                        .put("mediaMid", s.mediaMid)
                        .put("singerMid", s.singerMid)
                        .put("payPlay", s.payPlay)
                        .put("payMonth", s.payMonth)
                        .put("priceTrack", s.priceTrack)
                        .put("priceAlbum", s.priceAlbum)
                        .put("size128", s.size128)
                        .put("size320", s.size320)
                        .put("sizeFlac", s.sizeFlac)
                        .put("sizeDolby", s.sizeDolby)
                        .put("sizeNew", JSONArray(s.sizeNew)),
                )
            }
            val root = JSONObject()
                .put("index", index)
                .put("position", positionMs)
                .put("songs", arr)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_DATA, root.toString()).apply()
        }
    }

    fun load(context: Context): Snapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_DATA, null) ?: return null
        val root = JSONObject(raw)
        val arr = root.optJSONArray("songs") ?: return null
        val songs = ArrayList<MusicApi.Song>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("mid")
            if (mid.isEmpty()) continue
            val sizeNew = o.optJSONArray("sizeNew")
            songs.add(
                MusicApi.Song(
                    id = o.optInt("id"),
                    mid = mid,
                    name = o.optString("name"),
                    singers = o.optString("singers"),
                    album = o.optString("album"),
                    albumMid = o.optString("albumMid"),
                    interval = o.optInt("interval"),
                    mediaMid = o.optString("mediaMid"),
                    singerMid = o.optString("singerMid"),
                    payPlay = o.optInt("payPlay"),
                    payMonth = o.optInt("payMonth"),
                    priceTrack = o.optInt("priceTrack"),
                    priceAlbum = o.optInt("priceAlbum"),
                    size128 = o.optInt("size128"),
                    size320 = o.optInt("size320"),
                    sizeFlac = o.optInt("sizeFlac"),
                    sizeDolby = o.optInt("sizeDolby"),
                    sizeNew = (0 until (sizeNew?.length() ?: 0)).map { sizeNew!!.optInt(it) },
                )
            )
        }
        if (songs.isEmpty()) return null
        Snapshot(songs, root.optInt("index", 0), root.optLong("position", 0L))
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
