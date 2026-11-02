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

    /**
     * 搜索：移动端接口（登录态最好）-> 综合搜索适配器（匿名也能用）-> smartbox 快搜。
     * 只走一个接口时经常整页空白，所以按顺序兜底。
     */
    fun search(keyword: String, page: Int = 1): List<Song> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        runCatching { searchMobile(q, page) }.getOrDefault(emptyList()).let { if (it.isNotEmpty()) return it }
        runCatching { searchAdaptor(q, page) }.getOrDefault(emptyList()).let { if (it.isNotEmpty()) return it }
        runCatching { legacySearch(q, page) }.getOrDefault(emptyList()).let { if (it.isNotEmpty()) return it }
        return smartBox(q).songs
    }

    private fun searchMobile(keyword: String, page: Int): List<Song> {
        val p = JSONObject()
        p.put("searchid", UUID.randomUUID().toString().replace("-", ""))
        p.put("query", keyword)
        p.put("search_type", 0)
        p.put("num_per_page", 30)
        p.put("page_num", page)
        p.put("highlight", true)
        p.put("grp", true)
        p.put("selectors", JSONObject())
        p.put("vec_selectors", org.json.JSONArray())
        val data = QqClient.cgi(
            "music.search.SearchCgiService",
            "DoSearchForQQMusicMobile",
            p,
            style = QqClient.CommStyle.ANDROID,
        )
        val arr = data.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    /** 综合搜索（music.adaptor.SearchAdaptor）的 body，歌曲在 item_song.items。 */
    fun adaptorBody(keyword: String, page: Int = 1): JSONObject? {
        val p = JSONObject()
        p.put("searchid", UUID.randomUUID().toString().replace("-", ""))
        p.put("search_type", 100)
        p.put("page_num", 15)
        p.put("query", keyword)
        p.put("page_id", page)
        p.put("highlight", true)
        p.put("grp", true)
        fun once(style: QqClient.CommStyle?): JSONObject? = runCatching {
            val data = QqClient.cgi("music.adaptor.SearchAdaptor", "do_search_v2", p, style = style)
            data.optJSONObject("body") ?: data
        }.getOrNull()
        val first = once(null)
        if (first != null && hasSongs(first)) return first
        // 登录态下偶发整块空：匿名再试一次（公开搜索，不依赖票据）
        val anon = once(QqClient.CommStyle.ANON)
        return if (anon != null && hasSongs(anon)) anon else (first ?: anon)
    }

    private fun hasSongs(body: JSONObject): Boolean =
        (body.optJSONObject("item_song")?.optJSONArray("items")?.length() ?: 0) > 0

    private fun searchAdaptor(keyword: String, page: Int): List<Song> {
        val body = adaptorBody(keyword, page) ?: return emptyList()
        val arr = body.optJSONObject("item_song")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    /**
     * 老版网页搜索 client_search_cp：匿名可用、一次 20 条、字段完整（含专辑 mid，所以有封面）。
     * 前面几条链路都空的时候靠它兜底，避免只剩 smartbox 的 4 条。
     */
    fun legacySearch(keyword: String, page: Int = 1, num: Int = 20): List<Song> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=" + page +
            "&n=" + num + "&w=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&format=json&cr=1&new_json=1&t=0&aggr=1&lossless=0&catZhida=1&platform=wk_v17&needNewCode=0"
        val text = QqClient.get(url, emptyMap()).use { it.body?.string().orEmpty() }
        if (text.isEmpty()) return emptyList()
        val arr = JSONObject(text).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    /** smartbox 快搜结果（少但稳定），歌曲 / 歌手 / 专辑三类都有。 */
    data class SmartBox(
        val songs: List<Song> = emptyList(),
        val singers: List<SingerApi.Singer> = emptyList(),
        val albums: List<AlbumApi.Album> = emptyList(),
    )

    /** 最后的兜底：smartbox 快搜，没有登录态也能搜到几首。 */
    fun smartBox(keyword: String): SmartBox = runCatching {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") + "&format=json&inCharset=utf-8&outCharset=utf-8"
        val text = QqClient.get(url, emptyMap()).use { it.body?.string().orEmpty() }
        if (text.isEmpty()) return SmartBox()
        val d = JSONObject(text).optJSONObject("data") ?: return SmartBox()
        val songs = ArrayList<Song>()
        d.optJSONObject("song")?.optJSONArray("itemlist")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val mid = o.optString("mid").ifEmpty { o.optString("id") }
                if (mid.isEmpty()) continue
                songs.add(
                    Song(
                        id = o.optInt("id", 0),
                        mid = mid,
                        name = o.optString("name"),
                        singers = o.optString("singer").replace("/", " / "),
                        album = "",
                        albumMid = "",
                        interval = 0,
                        mediaMid = mid,
                        singerMid = "",
                    )
                )
            }
        }
        // smartbox 只给 mid/名称，没有专辑与时长；补一次 songDetail，列表才有封面
        val enriched = songs.map { s ->
            if (s.id <= 0) return@map s
            val full = runCatching { detail(s.id.toLong()) }.getOrNull() ?: return@map s
            full.copy(
                name = full.name.ifEmpty { s.name },
                singers = full.singers.ifEmpty { s.singers },
                mid = full.mid.ifEmpty { s.mid },
            )
        }
        val singers = ArrayList<SingerApi.Singer>()
        d.optJSONObject("singer")?.optJSONArray("itemlist")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val mid = o.optString("mid")
                val name = o.optString("name")
                if (mid.isNotEmpty() && name.isNotEmpty()) singers.add(SingerApi.Singer(mid, name))
            }
        }
        val albums = ArrayList<AlbumApi.Album>()
        d.optJSONObject("album")?.optJSONArray("itemlist")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val mid = o.optString("mid")
                if (mid.isEmpty()) continue
                albums.add(
                    AlbumApi.Album(
                        mid, o.optString("name"), o.optString("singer"),
                        o.optString("pic").replaceFirst("http://", "https://"),
                    )
                )
            }
        }
        SmartBox(enriched, singers, albums)
    }.getOrDefault(SmartBox())

    /** 歌曲显示名：优先 title（同名不同版本靠它区分），带高亮标签的字符串一律不采用。 */
    private fun displayName(o: JSONObject): String {
        val n = o.optString("name")
        val t = o.optString("title")
        return when {
            t.isEmpty() || t.contains("<") -> n
            n.isEmpty() -> t
            t.length > n.length -> t
            else -> n
        }
    }

    fun parseSong(o: JSONObject?): Song? {
        if (o == null) return null
        // 专辑曲目 / 歌手歌曲 / 雷达推荐等的元素常是 { songInfo: {...} } 包一层，
        // 不解包的话每个元素都拿不到 mid，整页就成了「暂无歌曲」。
        o.optJSONObject("songInfo")?.let { return parseSong(it) }
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
            name = displayName(o),
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
    /** 一次 vkey 请求的完整结果：直链、原始 purl（用于换 CDN 节点）、以及服务端给的 CDN 列表。 */
    data class StreamRef(
        val urls: Map<String, String>,
        val raws: Map<String, String>,
        val sips: List<String>,
    )

    fun streamBatch(song: Song, codes: List<String>): Map<String, String> = streamBatchEx(song, codes).urls

    fun streamBatchEx(song: Song, codes: List<String>): StreamRef {
        if (codes.isEmpty()) return StreamRef(emptyMap(), emptyMap(), emptyList())
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
        val data = runCatching { QqClient.cgi("music.vkey.GetVkey", "UrlGetVkey", p) }.getOrNull()
            ?: return StreamRef(emptyMap(), emptyMap(), emptyList())
        val arr = data.optJSONArray("midurlinfo") ?: return StreamRef(emptyMap(), emptyMap(), emptyList())
        // 官方客户端就是从这里拿节点列表择优的
        val sips = ArrayList<String>()
        data.optJSONArray("sip")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { sips.add(it) }
        }
        val out = HashMap<String, String>()
        val raws = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val info = arr.optJSONObject(i) ?: continue
            val purl = info.optString("purl")
            if (purl.isEmpty()) continue
            val fn = info.optString("filename")
            val code = codes.firstOrNull { fn.startsWith(it) } ?: codes.getOrNull(i) ?: continue
            raws[code] = purl
            out[code] = if (purl.startsWith("http")) purl else "https://dl.stream.qqmusic.qq.com/" + purl
        }
        return StreamRef(out, raws, sips)
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
