package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.UserApi

/**
 * 歌曲可播/可下载状态，字段语义依据 QQMusicApi（L-1124/QQMusicApi）的模型定义：
 *
 *  - pay.pay_month = 1  → 需要绿钻 / 付费包
 *  - pay.pay_play  = 1  → 播放需要付费（不等于整首不可播，试听片段仍可能可用）
 *  - pay.pay_down  = 1  → 下载需要付费
 *  - pay.price_track / price_album > 0 → 单曲售价 / 专辑售价（单位：分），需要单独购买
 *  - file.size_128mp3 / size_320mp3 / size_flac 为 0 → 该音质没有可下发的文件
 *
 * 真正的「能不能播」以 vkey 返回的 result 为准（见 [TrackResolver]）：
 *  0 成功 / 104003 无权限 / 104004 VKey 失败 / 104013 设备受限。
 */
object SongAccess {

    enum class Kind { FREE, VIP, PAID_TRACK, PAID_ALBUM, NO_COPYRIGHT }

    data class Info(val kind: Kind, val label: String, val hint: String)

    fun of(song: MusicApi.Song): Info {
        val vip = UserApi.Account.isVip
        val hasFile = song.size128 > 0 || song.size320 > 0 || song.sizeFlac > 0
        return when {
            // 三个文件大小全为 0：没有任何可下发文件（下架 / 无版权）
            !hasFile -> Info(Kind.NO_COPYRIGHT, "无版权", "这首歌当前没有可用音源（下架或无版权）")
            // 单曲售价 / 专辑售价 > 0：数字单曲、数字专辑，需要单独购买
            song.priceTrack > 0 -> Info(Kind.PAID_TRACK, "单曲付费", "数字单曲，需单独购买（" + fen(song.priceTrack) + "）")
            song.priceAlbum > 0 -> Info(Kind.PAID_ALBUM, "专辑付费", "出自数字专辑，需购买专辑（" + fen(song.priceAlbum) + "）")
            // pay_month = 1：需要绿钻 / 付费包
            song.payMonth == 1 && !vip -> Info(Kind.VIP, "绿钻", "需要绿钻或付费包才能播放")
            else -> Info(Kind.FREE, "", "")
        }
    }

    private fun fen(v: Int): String = if (v >= 100) (v / 100).toString() + " 元" else v.toString() + " 分"

    /** 列表里需要展示徽章的场合用；免费歌返回空串。 */
    fun badge(song: MusicApi.Song): String = of(song).label

    fun canPlayFull(song: MusicApi.Song): Boolean = of(song).kind == Kind.FREE
}
