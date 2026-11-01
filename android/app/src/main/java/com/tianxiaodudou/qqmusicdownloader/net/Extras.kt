package com.tianxiaodudou.qqmusicdownloader.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 参考 QQMusicApi 补齐的扩展能力：相似歌曲、相关歌单、其他版本、标签/制作人、
 * CDN 调度、歌曲替换、专辑详情/收藏、歌手详情/专辑/相似、搜索联想与分类搜索、
 * 歌单删除/收藏列表、登录态检查/登出。
 */
object Extras {

    // ---------------- 歌曲 ----------------

    fun similarSongs(songId: Int): List<MusicApi.Song> = runCatching {
        val data = QqClient.cgi(
            "music.recommend.TrackRelationServer",
            "GetSimilarSongs",
            JSONObject().put("songid", songId),
        )
        val out = ArrayList<MusicApi.Song>()
        val groups = data.optJSONArray("vecSongNew") ?: return emptyList()
        for (i in 0 until groups.length()) {
            val songs = groups.optJSONObject(i)?.optJSONArray("songs") ?: continue
            for (j in 0 until songs.length()) {
                val track = songs.optJSONObject(j)?.optJSONObject("track") ?: continue
                MusicApi.parseSong(track)?.let { out.add(it) }
            }
        }
        out
    }.getOrDefault(emptyList())

    fun relatedPlaylists(songId: Int): List<PlaylistApi.Item> = runCatching {
        val data = QqClient.cgi(
            "music.recommend.TrackRelationServer",
            "GetRelatedPlaylist",
            JSONObject().put("songid", songId).put("vecPlaylist", JSONArray()),
        )
        val out = ArrayList<PlaylistApi.Item>()
        val groups = data.optJSONArray("vecPlaylistNew") ?: return emptyList()
        for (i in 0 until groups.length()) {
            val lists = groups.optJSONObject(i)?.optJSONArray("playlists") ?: continue
            for (j in 0 until lists.length()) {
                val o = lists.optJSONObject(j) ?: continue
                val id = o.optString("tid").ifEmpty { o.optString("dissid") }
                if (id.isEmpty()) continue
                out.add(
                    PlaylistApi.Item(
                        id = id,
                        dirId = o.optString("dirId"),
                        name = o.optString("name").ifEmpty { o.optString("title") },
                        count = o.optInt("songnum", o.optInt("song_cnt", 0)),
                    )
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    fun otherVersions(song: MusicApi.Song): List<MusicApi.Song> = runCatching {
        val p = if (song.id > 0) JSONObject().put("songid", song.id) else JSONObject().put("songmid", song.mid)
        val data = QqClient.cgi("music.musichallSong.OtherVersionServer", "GetOtherVersionSongs", p)
        val arr = data.optJSONArray("versionList") ?: return emptyList()
        (0 until arr.length()).mapNotNull { MusicApi.parseSong(arr.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun labels(songId: Int): List<String> = runCatching {
        val data = QqClient.cgi(
            "music.recommend.TrackRelationServer",
            "GetSongLabels",
            JSONObject().put("songid", songId),
        )
        val arr = data.optJSONArray("labels") ?: return emptyList()
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            o.optString("tag_txt").ifEmpty { o.optString("tagTxt") }.ifEmpty { o.optString("tag") }
        }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** 制作人：职位 -> 人名列表。 */
    fun producers(songId: Int): List<Pair<String, String>> = runCatching {
        val p = JSONObject().put("songid", songId)
        val data = QqClient.cgi("music.sociality.KolWorksTag", "SongProducer", p)
        val arr = data.optJSONArray("Lst") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val group = arr.optJSONObject(i) ?: continue
            val role = group.optString("Title").ifEmpty { group.optString("Role") }.ifEmpty { group.optString("Name") }
            val members = group.optJSONArray("Members") ?: group.optJSONArray("members")
            val names = if (members != null) {
                (0 until members.length()).mapNotNull { k ->
                    val m = members.optJSONObject(k) ?: return@mapNotNull null
                    m.optString("Name").ifEmpty { m.optString("name") }
                }.filter { it.isNotEmpty() }
            } else emptyList()
            if (role.isNotEmpty() && names.isNotEmpty()) out.add(role to names.joinToString("/"))
        }
        out
    }.getOrDefault(emptyList())

    fun favNumber(songId: Int): String = runCatching {
        val data = QqClient.cgi(
            "music.musicasset.SongFavRead",
            "GetSongFansNumberById",
            JSONObject().put("v_songId", JSONArray().put(songId)),
        )
        val show = data.optJSONObject("m_show") ?: return ""
        show.optString(songId.toString())
    }.getOrDefault("")

    fun hasSheet(mid: String): Boolean = runCatching {
        val data = QqClient.cgi(
            "music.mir.SheetMusicSvr",
            "HasSheetMusic",
            JSONObject().put("songMid", mid),
        )
        data.optBoolean("hasLdy") || data.optBoolean("hasQrcx") || data.optBoolean("hasGuitar") ||
            data.optBoolean("hasChongChong")
    }.getOrDefault(false)

    /** CDN 根地址列表，播放前拿来替换域名。 */
    fun cdnSips(): List<String> = runCatching {
        val data = QqClient.cgi(
            "music.audioCdnDispatch.cdnDispatch",
            "GetCdnDispatch",
            JSONObject().put("guid", QqClient.guid).put("uid", "0").put("use_new_domain", 1).put("use_ipv6", 1),
        )
        val arr = data.optJSONArray("sip") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.startsWith("http") } }
    }.getOrDefault(emptyList())

    /** 版权受限时换一个可播版本。 */
    fun replacement(song: MusicApi.Song): MusicApi.Song? = runCatching {
        val p = JSONObject()
            .put("ctx", 0)
            .put("client", 1)
            .put("types", JSONArray().put(0))
            .put("modify_stamp", JSONArray().put(0))
            .put("mids", JSONArray().put(song.mid))
        val data = QqClient.cgi("music.trackInfo.UniformRuleCtrl", "CgiGetTrackInfo", p)
        val arr = data.optJSONArray("tracks") ?: return null
        val alt = MusicApi.parseSong(arr.optJSONObject(0)) ?: return null
        if (alt.mid == song.mid) null else alt
    }.getOrNull()

    // ---------------- 专辑 ----------------

    data class AlbumDetail(
        val mid: String,
        val name: String,
        val singer: String,
        val cover: String,
        val publish: String,
        val genre: String,
        val company: String,
        val desc: String,
        val id: Long,
    )

    fun albumDetail(mid: String): AlbumDetail? = runCatching {
        val data = QqClient.cgi(
            "music.musichallAlbum.AlbumInfoServer",
            "GetAlbumDetail",
            JSONObject().put("albumMId", mid),
        )
        val b = data.optJSONObject("basicInfo") ?: return null
        val singerArr = data.optJSONObject("singer")?.optJSONArray("singerList")
        val singer = if (singerArr != null) {
            (0 until singerArr.length()).mapNotNull { singerArr.optJSONObject(it)?.optString("name") }
                .filter { it.isNotEmpty() }.joinToString(" / ")
        } else ""
        AlbumDetail(
            mid = b.optString("albumMid").ifEmpty { mid },
            name = b.optString("albumName").ifEmpty { b.optString("name") },
            singer = singer,
            cover = b.optString("picUrl").ifEmpty { b.optString("cover") },
            publish = b.optString("publishDate"),
            genre = b.optString("genre"),
            company = data.optJSONObject("company")?.optString("companyName").orEmpty(),
            desc = b.optString("desc"),
            id = b.optLong("albumID", b.optLong("albumId", 0)),
        )
    }.getOrNull()

    fun favAlbum(albumId: Long, fav: Boolean): Boolean = runCatching {
        val method = if (fav) "FavAlbum" else "CancelFavAlbum"
        val p = JSONObject().put("v_albumId", JSONArray().put(albumId))
        val data = QqClient.cgi("music.musicasset.AlbumFavWrite", method, p)
        data.optInt("code", data.optInt("retCode", 0)) == 0
    }.getOrDefault(false)

    /** 收藏的专辑（用户列表）。 */
    fun favAlbums(): List<AlbumDetail> = runCatching {
        val euin = QqClient.session?.encryptUin ?: return emptyList()
        val data = QqClient.cgi(
            "music.musicasset.AlbumFavRead",
            "CgiGetAlbumFavInfo",
            JSONObject().put("euin", euin).put("offset", 0).put("size", 100),
        )
        val arr = data.optJSONArray("albums") ?: data.optJSONArray("v_album") ?: return emptyList()
        val out = ArrayList<AlbumDetail>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("albumMid").ifEmpty { o.optString("mid") }
            if (mid.isEmpty()) continue
            out.add(
                AlbumDetail(
                    mid = mid,
                    name = o.optString("albumName").ifEmpty { o.optString("name") },
                    singer = o.optString("singerName"),
                    cover = o.optString("picUrl").ifEmpty { o.optString("cover") },
                    publish = o.optString("publishDate"),
                    genre = "",
                    company = "",
                    desc = "",
                    id = o.optLong("albumID", 0),
                )
            )
        }
        out
    }.getOrDefault(emptyList())

    // ---------------- 歌手 ----------------

    data class SingerInfo(
        val mid: String,
        val name: String,
        val avatar: String,
        val desc: String,
        val fans: String,
        val songs: String,
    )

    fun singerInfo(mid: String): SingerInfo? = runCatching {
        val data = QqClient.cgi(
            "music.UnifiedHomepage.UnifiedHomepageSrv",
            "GetHomepageHeader",
            JSONObject().put("SingerMid", mid),
            style = QqClient.CommStyle.ANDROID,
        )
        val singer = data.optJSONObject("Info")?.optJSONObject("Singer") ?: JSONObject()
        val base = data.optJSONObject("Info")?.optJSONObject("BaseInfo") ?: JSONObject()
        SingerInfo(
            mid = mid,
            name = singer.optString("Name").ifEmpty { base.optString("Name") },
            avatar = singer.optString("Avatar").ifEmpty { singer.optString("BigAvatar") }.ifEmpty { base.optString("Avatar") },
            desc = singer.optString("Desc"),
            fans = singer.optString("FansNum"),
            songs = singer.optString("SongNum"),
        )
    }.getOrNull()

    fun singerDesc(mid: String): String = runCatching {
        val data = QqClient.cgi(
            "music.musichallSinger.SingerInfoInter",
            "GetSingerDetail",
            JSONObject().put("singer_mids", JSONArray().put(mid))
                .put("group_singer", true).put("wiki_singer", true)
                .put("ex_singer", true).put("pic", true).put("photos", false),
        )
        val first = data.optJSONArray("singer_list")?.optJSONObject(0) ?: return ""
        first.optString("wiki")
    }.getOrDefault("")

    fun similarSingers(mid: String): List<SingerApi.Singer> = runCatching {
        val data = QqClient.cgi(
            "music.SimilarSingerSvr",
            "GetSimilarSingerList",
            JSONObject().put("singerMid", mid).put("number", 20),
        )
        val arr = data.optJSONArray("singerlist") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val m = o.optString("singer_mid").ifEmpty { o.optString("mid") }
            val n = o.optString("singer_name").ifEmpty { o.optString("name") }
            if (m.isEmpty() || n.isEmpty()) null else SingerApi.Singer(m, n)
        }
    }.getOrDefault(emptyList())

    fun singerAlbums(mid: String): List<AlbumApi.Album> = runCatching {
        val data = QqClient.cgi(
            "music.musichallAlbum.AlbumListServer",
            "GetAlbumList",
            JSONObject().put("singerMid", mid).put("order", 1).put("number", 50).put("begin", 0),
        )
        val arr = data.optJSONArray("albumList") ?: data.optJSONArray("album_list") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val m = o.optString("albumMid").ifEmpty { o.optString("album_mid") }
            if (m.isEmpty()) return@mapNotNull null
            AlbumApi.Album(
                mid = m,
                name = o.optString("albumName").ifEmpty { o.optString("name") },
                singer = o.optString("singerName"),
                cover = o.optString("picUrl").ifEmpty { o.optString("cover") },
            )
        }
    }.getOrDefault(emptyList())

    // ---------------- 搜索增强 ----------------

    fun complete(keyword: String): List<String> = runCatching {
        val data = QqClient.cgi(
            "music.smartboxCgi.SmartBoxCgi",
            "GetSmartBoxResult",
            JSONObject().put("search_id", System.currentTimeMillis().toString())
                .put("query", keyword).put("num_per_page", 0).put("page_idx", 0),
        )
        val arr = data.optJSONArray("item") ?: data.optJSONArray("items") ?: return emptyList()
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            o.optString("name").ifEmpty { o.optString("query") }
        }.filter { it.isNotEmpty() }.distinct().take(8)
    }.getOrDefault(emptyList())

    data class SearchResult(
        val songs: List<MusicApi.Song> = emptyList(),
        val playlists: List<PlaylistApi.Item> = emptyList(),
        val singers: List<SingerApi.Singer> = emptyList(),
        val albums: List<AlbumApi.Album> = emptyList(),
    )

    /** type: 0 单曲 / 1 歌手 / 2 专辑 / 3 歌单。 */
    fun searchByType(keyword: String, type: Int, page: Int = 1, num: Int = 15): SearchResult = runCatching {
        val p = JSONObject()
            .put("searchid", java.util.UUID.randomUUID().toString().replace("-", ""))
            .put("query", keyword)
            .put("search_type", type)
            .put("num_per_page", num)
            .put("page_num", page)
            .put("highlight", true)
            .put("grp", true)
            .put("selectors", JSONObject())
        // 参考实现固定用 Android 平台
        val data = QqClient.cgi(
            "music.search.SearchCgiService",
            "DoSearchForQQMusicMobile",
            p,
            style = QqClient.CommStyle.ANDROID,
        )
        val body = data.optJSONObject("body") ?: return SearchResult()
        val songs = ArrayList<MusicApi.Song>()
        body.optJSONObject("song")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) MusicApi.parseSong(arr.optJSONObject(i))?.let { songs.add(it) }
        }
        val playlists = ArrayList<PlaylistApi.Item>()
        body.optJSONObject("songlist")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("dissid").ifEmpty { o.optString("tid") }
                if (id.isEmpty()) continue
                playlists.add(
                    PlaylistApi.Item(id, o.optString("dirId"), o.optString("dissname").ifEmpty { o.optString("name") },
                        o.optInt("songnum", 0))
                )
            }
        }
        val singers = ArrayList<SingerApi.Singer>()
        body.optJSONObject("singer")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optString("mid").ifEmpty { o.optString("singer_mid") }
                val n = o.optString("name").ifEmpty { o.optString("singer_name") }
                if (m.isNotEmpty() && n.isNotEmpty()) singers.add(SingerApi.Singer(m, n))
            }
        }
        val albums = ArrayList<AlbumApi.Album>()
        body.optJSONObject("album")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optString("albumMid").ifEmpty { o.optString("mid") }
                if (m.isEmpty()) continue
                albums.add(
                    AlbumApi.Album(m, o.optString("albumName").ifEmpty { o.optString("name") },
                        o.optString("singerName"), o.optString("picUrl"))
                )
            }
        }
        if (songs.isNotEmpty() || playlists.isNotEmpty() || singers.isNotEmpty() || albums.isNotEmpty()) {
            return@runCatching SearchResult(songs, playlists, singers, albums)
        }
        // 移动端搜索在部分登录态下会整块回空：退到综合搜索适配器（匿名也返回曲目）
        val fallbackBody = MusicApi.adaptorBody(keyword, page) ?: return@runCatching SearchResult()
        fallbackBody.optJSONObject("item_song")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) MusicApi.parseSong(arr.optJSONObject(i))?.let { songs.add(it) }
        }
        fallbackBody.optJSONObject("item_songlist")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("dissid").ifEmpty { o.optString("tid") }.ifEmpty { o.optString("id") }
                if (id.isEmpty()) continue
                playlists.add(
                    PlaylistApi.Item(
                        id, o.optString("dirId"),
                        o.optString("dissname").ifEmpty { o.optString("name") }.ifEmpty { o.optString("title") },
                        o.optInt("songnum", 0),
                        PlaylistApi.coverOf(o),
                    )
                )
            }
        }
        fallbackBody.optJSONObject("item_album")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optString("albumMid").ifEmpty { o.optString("mid") }
                if (m.isEmpty()) continue
                albums.add(
                    AlbumApi.Album(
                        m,
                        o.optString("albumName").ifEmpty { o.optString("name") }.ifEmpty { o.optString("title") },
                        o.optString("singerName").ifEmpty { o.optString("singer") },
                        PlaylistApi.coverOf(o),
                    )
                )
            }
        }
        fallbackBody.optJSONObject("singer")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optString("mid").ifEmpty { o.optString("singer_mid") }
                val n = o.optString("name").ifEmpty { o.optString("singer_name") }
                if (m.isNotEmpty() && n.isNotEmpty()) singers.add(SingerApi.Singer(m, n))
            }
        }
        if (songs.isEmpty()) {
            songs.addAll(runCatching { MusicApi.legacySearch(keyword, page) }.getOrDefault(emptyList()))
        }
        if (songs.isEmpty() && singers.isEmpty() && albums.isEmpty()) {
            val box = MusicApi.smartBox(keyword)
            songs.addAll(box.songs)
            singers.addAll(box.singers)
            albums.addAll(box.albums)
        }
        SearchResult(songs, playlists, singers, albums)
    }.getOrDefault(SearchResult())

    // ---------------- 歌单扩展 ----------------

    fun deletePlaylist(dirId: Int): Boolean = runCatching {
        val data = QqClient.cgi(
            "music.musicasset.PlaylistBaseWrite",
            "DelPlaylist",
            JSONObject().put("dirId", dirId),
        )
        data.optInt("retCode", 0) == 0
    }.getOrDefault(false)

    fun favPlaylists(): List<PlaylistApi.Item> = runCatching {
        val euin = QqClient.session?.encryptUin ?: return emptyList()
        val data = QqClient.cgi(
            "music.musicasset.PlaylistFavRead",
            "CgiGetPlaylistFavInfo",
            JSONObject().put("uin", euin).put("offset", 0).put("size", 100),
        )
        val arr = data.optJSONArray("playlists") ?: data.optJSONArray("v_playlist") ?: return emptyList()
        val out = ArrayList<PlaylistApi.Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("tid").ifEmpty { o.optString("dissid") }
            if (id.isEmpty()) continue
            out.add(
                PlaylistApi.Item(
                    id = id,
                    dirId = o.optString("dirId"),
                    name = o.optString("name").ifEmpty { o.optString("title") },
                    count = o.optInt("songnum", 0),
                    cover = PlaylistApi.coverOf(o),
                )
            )
        }
        out
    }.getOrDefault(emptyList())

    // ---------------- 歌词词典 ----------------

    data class DictEntry(val phrase: String, val explain: String, val lyricText: String)

    fun aiDict(songId: Int): List<DictEntry> = runCatching {
        val data = QqClient.cgi(
            "music.musichallSong.PlayLyricInfo",
            "GetAIDictInfo",
            JSONObject().put("songID", songId),
        )
        val arr = data.optJSONArray("dictList") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val phrase = o.optString("phrase")
            if (phrase.isEmpty()) null
            else DictEntry(phrase, o.optString("explain"), o.optString("lyricText"))
        }
    }.getOrDefault(emptyList())

    // ---------------- 评论 ----------------

    data class Comment(
        val id: String,
        val nick: String,
        val avatar: String,
        val content: String,
        val time: Long,
        val likes: Int,
    )

    fun comments(songId: Int, hot: Boolean): List<Comment> = runCatching {
        val method = if (hot) "GetHotCommentList" else "GetNewCommentList"
        val p = JSONObject()
            .put("BizType", 1)
            .put("BizId", songId.toString())
            .put("LastCommentSeqNo", "")
            .put("PageSize", 20)
            .put("PageNum", 0)
            .put("HotType", 1)
            .put("WithAirborne", 0)
            .put("PicEnable", 1)
            .put("BizSubType", 2)
        val data = QqClient.cgi("music.globalComment.CommentRead", method, p)
        val arr = data.optJSONObject("CommentList")?.optJSONArray("Comments")
            ?: data.optJSONArray("comments")
            ?: return emptyList()
        val out = ArrayList<Comment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("Content")
            if (content.isEmpty()) continue
            out.add(
                Comment(
                    id = o.optString("CmId"),
                    nick = o.optString("Nick").ifEmpty { "匿名用户" },
                    avatar = o.optString("Avatar"),
                    content = content,
                    time = o.optLong("PubTime", 0L),
                    likes = o.optInt("PraiseNum", 0),
                )
            )
        }
        out
    }.getOrDefault(emptyList())

    fun addComment(songId: Int, text: String): Boolean = runCatching {
        val p = JSONObject()
            .put("Content", text)
            .put("BizType", 1)
            .put("BizId", songId.toString())
            .put("BizSubType", 2)
        QqClient.cgi("music.globalComment.CommentWriteServer", "AddComment", p)
        true
    }.getOrDefault(false)

    // ---------------- 关注 / 粉丝 / 好友 ----------------

    data class RelUser(val name: String, val avatar: String, val desc: String)

    /** kind: 0 关注歌手, 1 关注用户, 2 粉丝, 3 好友。 */
    fun relations(kind: Int): List<RelUser> = runCatching {
        val euin = QqClient.session?.encryptUin ?: return emptyList()
        val arr: JSONArray? = when (kind) {
            0, 1, 2 -> {
                val method = when (kind) {
                    0 -> "GetFollowSingerList"
                    1 -> "GetFollowUserList"
                    else -> "GetFansList"
                }
                val data = QqClient.cgi(
                    "music.concern.RelationList",
                    method,
                    JSONObject().put("HostUin", euin).put("From", 0).put("Size", 50),
                )
                data.optJSONArray("List") ?: data.optJSONArray("list")
            }
            else -> {
                val data = QqClient.cgi(
                    "music.homepage.Friendship",
                    "GetFriendList",
                    JSONObject().put("PageSize", 50).put("Page", 0),
                )
                data.optJSONArray("friends") ?: data.optJSONArray("List")
            }
        }
        if (arr == null) return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("Name").ifEmpty { o.optString("name") }.ifEmpty { o.optString("Nick") }
            if (name.isEmpty()) return@mapNotNull null
            RelUser(
                name = name,
                avatar = o.optString("AvatarUrl").ifEmpty { o.optString("Avatar") },
                desc = o.optString("Desc").ifEmpty { o.optString("desc") },
            )
        }
    }.getOrDefault(emptyList())

    // ---------------- 登录态 ----------------

    /** 返回 true 表示登录态仍然有效。 */
    fun checkLogin(): Boolean = runCatching {
        QqClient.cgi(
            "music.UserInfo.userInfoServer",
            "GetLoginUserInfo",
            JSONObject(),
            style = QqClient.CommStyle.ANDROID,
        )
        true
    }.getOrDefault(false)

    fun logout(): Boolean = runCatching {
        QqClient.cgi("music.login.LoginServer", "Logout", JSONObject(), retryAuth = false)
        true
    }.getOrDefault(false)
}
