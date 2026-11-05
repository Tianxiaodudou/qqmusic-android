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
        val superVip = UserApi.Account.isSuper
        val noAudio = song.size128 <= 0 && song.size320 <= 0 && song.sizeFlac <= 0
        return when {
            // 完全没有可用音频：下架/无版权
            noAudio -> Info(Kind.NONE, "无版权", "这首歌当前没有可用音源")
            // 数字单曲/专辑：必须购买
            song.paid -> Info(Kind.PAID, "付费", "数字单曲，购买后才能完整播放")
            // 需要会员：没会员标 VIP
            (song.payPlay == 1 || song.payMonth == 1) && !vip ->
                Info(Kind.VIP, "VIP", "需要绿钻会员才能完整播放（可用 30 秒试听）")
            // 需要超级会员才有高音质：标 VIP（无损/母带档位）
            song.sizeNew.isNotEmpty() && !superVip && song.sizeFlac <= 0 ->
                Info(Kind.VIP, "VIP", "该音质需要超级会员")
            else -> Info(Kind.FREE, "", "")
        }
    }

    fun canPlayFull(song: MusicApi.Song): Boolean = of(song).kind == Kind.FREE
}
