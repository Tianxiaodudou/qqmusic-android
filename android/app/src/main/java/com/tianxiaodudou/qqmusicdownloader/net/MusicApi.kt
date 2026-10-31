package com.tianxiaodudou.qqmusicdownloader.net

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object MusicApi {

    data class Song(
        val id: Int,
        val mid: String,
        val name: String,
        val singers: String,
        val album: String,
        val albumMid: String,
        val interval: Int,
        val mediaMid: String,
        val singerMid: String = "",
        /** pay_play = 1 表示需要单独购买（付费单曲 / 数字专辑），已购买会变回 0。 */
        val payPlay: Int = 0,
        val payMonth: Int = 0,
        val priceTrack: Int = 0,
        val priceAlbum: Int = 0,
        val size128: Int = 0,
        val size320: Int = 0,
        val sizeFlac: Int = 0,
        val sizeDolby: Int = 0,
        val sizeNew: List<Int> = emptyList(),
    ) {
        val subtitle: String get() = if (album.isEmpty()) singers else singers + " · " + album

        /**
         * 只有「需要单独购买、且不是会员就能听」的内容才算付费：
         * pay_month=1 表示绿钻可听，不能置灰，否则会把会员曲库全判成付费。
         */
        val paid: Boolean get() = payPlay == 1 && payMonth == 0 && (priceTrack > 0 || priceAlbum > 0)
    }

    fun search(keyword: String, page: Int = 1): List<Song> {
        val p = JSONObject()
        p.put("searchid", UUID.randomUUID().toString().replace("-", ""))
        p.put("query", keyword)
        p.put("search_type", 0)
        p.put("num_per_page", 30)
        p.put("page_num", page)
        p.put("highlight", true)
        p.put("grp", true)
        p.put("selectors", JSONObject())
        val data = QqClient.cgi("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", p)
        val arr = data.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun parseSong(o: JSONObject?): Song? {
        if (o == null) return null
        val mid = o.optString("mid")
        if (mid.isEmpty()) return null
        val names = StringBuilder()
        var firstSingerMid = ""
        val singers = o.optJSONArray("singer")
        if (singers != null) {
            for (j in 0 until singers.length()) {
                val so = singers.optJSONObject(j) ?: continue
                val s = so.optString("name")
                if (firstSingerMid.isEmpty()) firstSingerMid = so.optString("mid")
                if (s.isNotEmpty()) {
                    if (names.isNotEmpty()) names.append(" / ")
                    names.append(s)
                }
            }
        }
        val album = o.optJSONObject("album")
        val pay = o.optJSONObject("pay")
        val file = o.optJSONObject("file")
        return Song(
            id = o.optInt("id", o.optInt("songId", 0)),
            mid = mid,
            name = o.optString("name", o.optString("title")),
            singers = names.toString(),
            album = album?.optString("name").orEmpty(),
            albumMid = album?.optString("mid").orEmpty(),
            interval = o.optInt("interval", 0),
            mediaMid = file?.optString("media_mid").orEmpty().ifEmpty { mid },
            singerMid = firstSingerMid,
            payPlay = pay?.optInt("pay_play", 0) ?: 0,
            payMonth = pay?.optInt("pay_month", 0) ?: 0,
            priceTrack = pay?.optInt("price_track", 0) ?: 0,
            priceAlbum = pay?.optInt("price_album", 0) ?: 0,
            size128 = file?.optInt("size_128mp3", 0) ?: 0,
            size320 = file?.optInt("size_320mp3", 0) ?: 0,
            sizeFlac = file?.optInt("size_flac", 0) ?: 0,
            sizeDolby = file?.optInt("size_dolby", 0) ?: 0,
            sizeNew = file?.optJSONArray("size_new")?.let { a ->
                (0 until a.length()).map { a.optInt(it, 0) }
            } ?: emptyList(),
        )
    }

    /** 热搜词：music.musicsearch.HotkeyService / GetHotkeyForQQMusicMobile。 */
    fun hotKeys(): List<String> {
        val p = JSONObject()
        p.put("search_id", searchId())
        val data = QqClient.cgi("music.musicsearch.HotkeyService", "GetHotkeyForQQMusicMobile", p)
        val arr = data.optJSONArray("vec_hotkey") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i)?.optString("query").orEmpty()
            if (q.isNotEmpty() && !out.contains(q)) out.add(q)
        }
        return out
    }

    private fun searchId(): String {
        val e = kotlin.random.Random.nextInt(1, 21).toLong()
        val n = kotlin.random.Random.nextInt(0, 4194304).toLong()
        val r = System.currentTimeMillis() % (24L * 60 * 60 * 1000)
        return (e * 18014398509481984L + n * 4294967296L + r).toString()
    }

    /** 按数字 songid 取详情（首页信息流的歌曲卡只带数字 ID）。 */
    fun detail(songId: Long): Song? {
        val p = JSONObject()
        p.put("song_id", songId)
        val data = QqClient.cgi("music.pf_song_detail_svr", "get_song_detail_yqq", p)
        val o = data.optJSONObject("track")
            ?: data.optJSONObject("song")
            ?: data.optJSONObject("track_info")
            ?: return null
        return parseSong(o)
    }

    /** 直链 + 实际拿到的档位代码（M800 = 320k，M500 = 128k）。 */
    data class Stream(val url: String, val code: String)

    /** 按音质取直链；目标码率没权限时自动退回 320k/128k。 */
    fun streamUrl(song: Song, quality: Settings.Quality): Stream? {
        if (quality.code.isNotEmpty()) {
            vkey(song, quality.code)?.let { return Stream(it, quality.code) }
        }
        vkey(song, "M800")?.let { return Stream(it, "M800") }
        vkey(song, "M500")?.let { return Stream(it, "M500") }
        return null
    }

    fun streamUrlOf(song: Song, code: String): String? = vkey(song, code)

    /**
     * 一次请求拿多档直链：把候选码率打包进一次 GetVkey，避免逐档串行请求，
     * 起播少等一两秒。返回 码率代码 -> 播放地址。
     */
    fun streamBatch(song: Song, codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        val p = JSONObject()
        p.put("uin", QqClient.session?.uin ?: "0")
        val names = JSONArray()
        val mids = JSONArray()
        val types = JSONArray()
        for (c in codes) {
            names.put(c + song.mediaMid + extOf(c))
            mids.put(song.mid)
            types.put(0)
        }
        p.put("filename", names)
        p.put("guid", QqClient.guid)
        p.put("songmid", mids)
        p.put("songtype", types)
        p.put("ctx", 0)
        val data = runCatching { QqClient.cgi("music.vkey.GetVkey", "UrlGetVkey", p) }.getOrNull() ?: return emptyMap()
        val arr = data.optJSONArray("midurlinfo") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val info = arr.optJSONObject(i) ?: continue
            val purl = info.optString("purl")
            if (purl.isEmpty()) continue
            val fn = info.optString("filename")
            val code = codes.firstOrNull { fn.startsWith(it) } ?: codes.getOrNull(i) ?: continue
            out[code] = if (purl.startsWith("http")) purl else "https://dl.stream.qqmusic.qq.com/" + purl
        }
        return out
    }

    private fun extOf(code: String): String = when {
        code.startsWith("M") -> ".mp3"
        code.startsWith("F") || code.startsWith("AI") || code.startsWith("Q0") -> ".flac"
        code.startsWith("O8") || code.startsWith("O6") -> ".ogg"
        code.startsWith("D0") || code.startsWith("DT") -> ".mp4"
        else -> ".mp3"
    }

    /** 兼容旧调用：优先 320k，再 128k。 */
    fun songUrl(song: Song): String? = streamUrl(song, Settings.Quality.HIGH)?.url

    /** 无损：拿加密直链 + ekey。返回 null 表示这首歌没有 SQ 权限。 */
    fun lossless(song: Song): Pair<String, String>? = losslessOf(song, "F0M0", ".mflac")

    /** 任意加密档位（mflac/mgg/mmp4）的直链 + ekey。 */
    fun losslessOf(song: Song, code: String, ext: String): Pair<String, String>? {
        val p = JSONObject()
        p.put("uin", QqClient.session?.uin ?: "0")
        p.put("filename", JSONArray().put(code + song.mediaMid + ext))
        p.put("guid", QqClient.guid)
        p.put("songmid", JSONArray().put(song.mid))
        p.put("songtype", JSONArray().put(0))
        p.put("ctx", 0)
        val data = runCatching { QqClient.cgi("music.vkey.GetEVkey", "CgiGetEVkey", p) }.getOrNull() ?: return null
        val info = data.optJSONArray("midurlinfo")?.optJSONObject(0) ?: return null
        val purl = info.optString("purl").orEmpty()
        val ekey = info.optString("ekey").orEmpty()
        if (purl.isEmpty() || ekey.isEmpty()) return null
        return Pair(if (purl.startsWith("http")) purl else "https://dl.stream.qqmusic.qq.com/" + purl, ekey)
    }

    private fun vkey(song: Song, quality: String): String? {
        val p = JSONObject()
        p.put("uin", QqClient.session?.uin ?: "0")
        p.put("filename", JSONArray().put(quality + song.mediaMid + ".mp3"))
        p.put("guid", QqClient.guid)
        p.put("songmid", JSONArray().put(song.mid))
        p.put("songtype", JSONArray().put(0))
        p.put("ctx", 0)
        val data = runCatching { QqClient.cgi("music.vkey.GetVkey", "UrlGetVkey", p) }.getOrNull() ?: return null
        val info = data.optJSONArray("midurlinfo") ?: return null
        val purl = info.optJSONObject(0)?.optString("purl").orEmpty()
        if (purl.isEmpty()) return null
        return if (purl.startsWith("http")) purl else "https://dl.stream.qqmusic.qq.com/" + purl
    }
}
