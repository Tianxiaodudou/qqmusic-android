package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings

/**
 * 歌曲各音质档位的文件大小。
 *
 * 之前音质选择器和「音频状态卡片」各写了一套映射，卡片里 Hi-Res 落到 else 分支显示成了 FLAC 的大小，
 * 于是同一个 Hi-Res 曲目在面板里是 229MB、卡片里是 134MB。统一到这里，两边都调用同一份。
 */
object SongSize {

    /** 返回字节数，0 表示接口没给。 */
    fun bytesOf(song: MusicApi.Song, quality: Settings.Quality): Long = when (quality) {
        Settings.Quality.STANDARD -> song.size128.toLong()
        Settings.Quality.HIGH -> song.size320.toLong()
        Settings.Quality.LOSSLESS -> song.sizeFlac.toLong()
        // Hi-Res / 母带的大小在 size_new 里（不同曲目给的档位数量不同）
        Settings.Quality.HIRES -> song.sizeNew.firstOrNull { it > 0 }?.toLong() ?: 0L
        Settings.Quality.MASTER -> song.sizeNew.lastOrNull { it > 0 }?.toLong() ?: 0L
        Settings.Quality.DOLBY -> song.sizeDolby.toLong()
    }

    /** 把「实际在播的音质」文案映射回档位；认不出来时返回 null。 */
    fun qualityOfLabel(label: String): Settings.Quality? = when {
        label.contains("母带") -> Settings.Quality.MASTER
        label.contains("Hi-Res", true) || label.contains("HIRES", true) -> Settings.Quality.HIRES
        label.contains("杜比") -> Settings.Quality.DOLBY
        label.contains("无损") -> Settings.Quality.LOSSLESS
        label.contains("高品质") || label.contains("320") -> Settings.Quality.HIGH
        label.contains("标准") || label.contains("128") -> Settings.Quality.STANDARD
        else -> null
    }

    fun fmt(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 100) String.format("%.0f MB", mb) else String.format("%.1f MB", mb)
    }
}
