package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.UserApi

/**
 * 歌曲在当前账号下的可播性判断。
 *
 * QQ 的字段含义：
 *  - pay_play / pay_month：需要会员才能完整播放/下载（绿钻/超级会员）
 *  - price_track / price_album：数字单曲/数字专辑，需要单独购买
 *  - size_128mp3/size_320mp3/size_flac 都为 0：没有任何可用音频（下架/无版权）
 */
object SongAccess {

    enum class Kind { FREE, VIP, PAID, NONE }

    data class Info(val kind: Kind, val label: String, val hint: String)

    fun of(song: MusicApi.Song): Info {
        val vip = UserApi.Account.isVip
        val noAudio = song.size128 <= 0 && song.size320 <= 0 && song.sizeFlac <= 0
        return when {
            // 完全没有可用音频：下架/无版权
            noAudio -> Info(Kind.NONE, "无版权", "这首歌当前没有可用音源")
            // 数字单曲/专辑：必须购买（这个字段最可靠）
            song.priceTrack > 0 || song.priceAlbum > 0 ->
                Info(Kind.PAID, "付费", "数字单曲/数字专辑，购买后才能完整播放")
            // 需要会员：只有在「连 128k 免费版都没有」时才算真要会员，
            // 否则免费歌曲也会被 pay_play 误标成 VIP（之前的误报就是这里来的）
            song.payPlay == 1 && song.size128 <= 0 && !vip ->
                Info(Kind.VIP, "VIP", "需要绿钻会员才能播放")
            else -> Info(Kind.FREE, "", "")
        }
    }

    fun canPlayFull(song: MusicApi.Song): Boolean = of(song).kind == Kind.FREE
}
