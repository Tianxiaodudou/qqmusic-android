package com.tianxiaodudou.qqmusicdownloader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** 歌单：我的歌单列表 + 歌单里的歌曲。 */
object PlaylistApi {

    data class Item(
        val id: String,
        val dirId: String,
        val name: String,
        val count: Int,
        val cover: String = "",
    )

    /** 歌单封面的候选字段（公开歌单有 picurl，自建歌单常见只有 albumPicUrl / bigpicUrl）。 */
    fun coverOf(o: JSONObject): String {
        for (k in listOf("picurl", "picUrl", "bigpicUrl", "bigPicUrl", "albumPicUrl", "album_pic_url", "cover", "logo", "pic")) {
            val v = o.optString(k)
            if (v.startsWith("http")) return v
        }
        return ""
    }

    /** 自建歌单服务端只给空封面时，退化成第一首歌的专辑封面。 */
    fun fallbackCover(item: Item): String = runCatching {
        if (item.count <= 0) return ""
        val first = songs(item, page = 1, num = 1).firstOrNull() ?: return ""
        if (first.albumMid.isEmpty()) "" else
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + first.albumMid + ".jpg"
    }.getOrDefault("")

    /** 批量补封面：只对空封面的歌单补，最多并发 3 个请求。 */
    suspend fun withCovers(items: List<Item>): List<Item> {
        if (items.none { it.cover.isEmpty() && it.count > 0 }) return items
        val out = items.toMutableList()
        for (range in items.indices.chunked(3)) {
            coroutineScope {
                val done = range.map { i ->
                    async(Dispatchers.IO) {
                        val item = out[i]
                        if (item.cover.isEmpty()) item.copy(cover = fallbackCover(item)) else item
                    }
                }.awaitAll()
                done.forEachIndexed { k, v -> out[range[k]] = v }
            }
        }
        return out
    }

    fun mine(): List<Item> {
        val uin = QqClient.session?.uin ?: return emptyList()
        val data = QqClient.cgi("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", JSONObject().put("uin", uin))
        val arr = data.optJSONArray("v_playlist")
            ?: data.optJSONArray("playlists")
            ?: data.optJSONArray("content")
            ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("tid").ifEmpty { o.optString("disstid") }
            if (id.isEmpty()) continue
            out.add(
                Item(
                    id = id,
                    dirId = o.optString("dirId").ifEmpty { o.optString("dirid") },
                    name = o.optString("dirName").ifEmpty { o.optString("name") },
                    count = o.optInt("songNum", o.optInt("songnum", 0)),
                    cover = coverOf(o),
                )
            )
        }
        return out
    }

    /** 加到指定歌单或「我喜欢」（dirId 201）。 */
    fun addSong(dirId: Int, tid: Int, song: MusicApi.Song): Boolean =
        addSongs(dirId, tid, listOf(song))

    /** 批量加歌：一次请求带全部 songId，比逐首快得多。 */
    fun addSongs(dirId: Int, tid: Int, songs: List<MusicApi.Song>): Boolean {
        if (songs.isEmpty()) return false
        val info = org.json.JSONArray()
        for (s in songs) info.put(org.json.JSONObject().put("songId", s.id).put("songType", 0))
        val p = JSONObject()
        p.put("dirId", dirId)
        p.put("tid", tid)
        p.put("bFmtUtf8", true)
        p.put("v_songInfo", info)
        val data = QqClient.cgi("music.musicasset.PlaylistDetailWrite", "AddSonglist", p)
        return data.optInt("retCode", 0) == 0
    }

    /** 新建歌单，成功返回新歌单（dirId/tid 由服务端下发）。 */
    fun create(name: String): Item? {
        val p = JSONObject().put("dirName", name.trim())
        val data = QqClient.cgi("music.musicasset.PlaylistBaseWrite", "AddPlaylist", p)
        val result = data.optJSONObject("result") ?: data
        val ret = data.optInt("retCode", result.optInt("retCode", 0))
        if (ret != 0) return null
        val tid = result.optString("tid")
        val dirId = result.optString("dirId")
        if (tid.isEmpty()) return null
        return Item(
            id = tid,
            dirId = dirId,
            name = result.optString("dirName").ifEmpty { name },
            count = 0,
        )
    }

    /** 收藏别人的公开歌单（disstid）。加密 uin 与明文 uin 都试一遍，兼容老登录态。 */
    fun favPlaylist(disstid: Long): Boolean {
        val s = QqClient.session ?: return false
        val candidates = listOf(s.encryptUin, s.uin).filter { it.isNotEmpty() }.distinct()
        for (uin in candidates) {
            val p = JSONObject()
            p.put("uin", uin)
            p.put("v_playlistId", org.json.JSONArray().put(disstid))
            val data = runCatching {
                QqClient.cgi("music.musicasset.PlaylistFavWrite", "FavPlaylist", p, retryAuth = false)
            }.getOrNull() ?: continue
            val failed = data.optJSONArray("v_failedPlaylistId")
            if (data.optInt("result", 0) == 0 && (failed == null || failed.length() == 0)) return true
        }
        return false
    }

    fun like(song: MusicApi.Song): Boolean = addSong(201, 0, song)

    /** 取消收藏（从「我喜欢」移出）。 */
    fun unlike(song: MusicApi.Song): Boolean {
        val info = org.json.JSONArray().put(org.json.JSONObject().put("songId", song.id).put("songType", 0))
        val p = JSONObject()
        p.put("dirId", 201)
        p.put("tid", 0)
        p.put("v_songInfo", info)
        val data = QqClient.cgi("music.musicasset.PlaylistDetailWrite", "DelSonglist", p)
        return data.optInt("retCode", 0) == 0
    }

    /** 「我喜欢」（dirId 201）走收藏接口：disstid=0 + dirid=201 + enc_host_uin。 */
    fun favSongs(page: Int = 1, num: Int = 100): List<MusicApi.Song> {
        val uin = QqClient.session?.uin ?: return emptyList()
        val p = JSONObject()
        p.put("disstid", 0)
        p.put("dirid", 201)
        p.put("tag", true)
        p.put("song_begin", num * (page - 1))
        p.put("song_num", num)
        p.put("userinfo", true)
        p.put("orderlist", true)
        p.put("enc_host_uin", uin.toString())
        val data = QqClient.cgi("music.srfDissInfo.DissInfo", "CgiGetDiss", p)
        val arr = data.optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<MusicApi.Song>()
        for (i in 0 until arr.length()) MusicApi.parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    fun songs(item: Item, page: Int = 1, num: Int = 100): List<MusicApi.Song> {
        if (item.dirId == "201" || item.id == "201") return favSongs(page, num)
        val p = JSONObject()
        // 这两个字段服务端按数字校验：传字符串会直接回 10004 param error。
        item.id.toLongOrNull()?.let { p.put("disstid", it) } ?: p.put("disstid", item.id)
        item.dirId.toLongOrNull()?.let { p.put("dirid", it) } ?: p.put("dirid", item.dirId.ifEmpty { "0" })
        p.put("tag", false)
        p.put("song_begin", num * (page - 1))
        p.put("song_num", num)
        p.put("userinfo", false)
        p.put("orderlist", true)
        p.put("onlysonglist", false)
        val data = QqClient.cgi("music.srfDissInfo.DissInfo", "CgiGetDiss", p)
        val arr = data.optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<MusicApi.Song>()
        for (i in 0 until arr.length()) MusicApi.parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }
}
