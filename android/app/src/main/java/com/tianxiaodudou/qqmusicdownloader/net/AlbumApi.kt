package com.tianxiaodudou.qqmusicdownloader.net

import org.json.JSONObject

/** 新碟上架 + 专辑曲目。 */
object AlbumApi {

    data class Album(val mid: String, val name: String, val singer: String, val cover: String)

    /** 新碟上架，area: 1内地 2港台 3欧美 4韩国 5日本 6其他。 */
    fun newAlbums(area: Int = 1, num: Int = 30, page: Int = 1): List<Album> {
        val p = JSONObject()
        p.put("area", area)
        p.put("num", num)
        p.put("start", num * (page - 1))
        val data = QqClient.cgi("newalbum.NewAlbumServer", "get_new_album_info", p)
        val arr = data.optJSONArray("albums")
            ?: data.optJSONArray("album_list")
            ?: data.optJSONArray("v_album")
            ?: return emptyList()
        val out = ArrayList<Album>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("mid").ifEmpty { o.optString("album_mid") }
            if (mid.isEmpty()) continue
            val singers = o.optJSONArray("singers") ?: o.optJSONArray("singer")
            val names = ArrayList<String>()
            if (singers != null) {
                for (j in 0 until singers.length()) {
                    val n = singers.optJSONObject(j)?.optString("name").orEmpty()
                    if (n.isNotEmpty() && !names.contains(n)) names.add(n)
                }
            }
            out.add(
                Album(
                    mid = mid,
                    name = o.optString("name").ifEmpty { o.optString("title") },
                    singer = names.joinToString("、"),
                    cover = o.optString("cover").ifEmpty { o.optString("picurl") },
                )
            )
        }
        return out
    }

    /** 按条数取新碟：单页最多 20，翻页凑够 n 张。 */
    fun newAlbumsUpTo(n: Int, area: Int = 1): List<Album> {
        val out = ArrayList<Album>()
        var page = 1
        while (out.size < n && page <= 5) {
            val batch = runCatching { newAlbums(area = area, num = 20, page = page) }.getOrDefault(emptyList())
            if (batch.isEmpty()) break
            out.addAll(batch)
            page += 1
        }
        return out.distinctBy { it.mid }.take(n.coerceAtLeast(1))
    }

    fun songs(mid: String, num: Int = 50): List<MusicApi.Song> {
        val p = JSONObject()
        p.put("albumMid", mid)
        p.put("begin", 0)
        p.put("num", num)
        val data = QqClient.cgi("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", p)
        val arr = data.optJSONArray("song_list") ?: data.optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<MusicApi.Song>()
        for (i in 0 until arr.length()) MusicApi.parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }
}
