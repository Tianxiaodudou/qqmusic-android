package com.tianxiaodudou.qqmusicdownloader.net


/**
 * 付费判定校准：搜索一大批歌，逐首探测 vkey 的 result，把「字段 + 实际能否播放」落盘，
 * 用于推导「免费 / 绿钻 / 单曲付费 / 无版权」的判别式。
 *
 * 触发：adb shell am start -n com.tianxiaodudou.qqmusicdownloader/.MainActivity --ez calib true
 */
object Calibrator {

    private val queries = listOf(
        "隔岸", "点歌的人", "起风了", "晴天", "孤勇者", "光年之外", "青花瓷", "演员",
        "小幸运", "后来", "稻香", "遥远的她", "追光者", "云烟成雨", "白月光与朱砂痣",
        "海阔天空", "夜空中最亮的星", "理想三旬", "成都", "斑马斑马",
        "南山南", "像我这样的人", "说散就散", "体面", "消愁",
    )

    fun run() {
        runCatching {
            val songs = LinkedHashMap<String, MusicApi.Song>()
            for (q in queries) {
                runCatching { MusicApi.search(q) }.getOrDefault(emptyList())
                    .take(8)
                    .forEach { songs[it.mid] = it }
            }
            val list = songs.values.toList()
            val r320 = HashMap<String, Int>()
            val rFlac = HashMap<String, Int>()
            list.chunked(25).forEach { chunk ->
                r320.putAll(runCatching { MusicApi.probeBatch(chunk, "M800") }.getOrDefault(emptyMap()))
                rFlac.putAll(runCatching { MusicApi.probeBatch(chunk, "F000") }.getOrDefault(emptyMap()))
            }
            list.forEach { s ->

                Diag.log(
                    "probe",
                    s.mid + " | " + s.name + " - " + s.singers +
                        " | payPlay=" + s.payPlay + " payMonth=" + s.payMonth +
                        " priceTrack=" + s.priceTrack + " priceAlbum=" + s.priceAlbum +
                        " s128=" + s.size128 + " s320=" + s.size320 + " flac=" + s.sizeFlac +
                        " | r320=" + (r320[s.mid] ?: -1) + " rFlac=" + (rFlac[s.mid] ?: -1),
                )
            }
            Diag.log("probe", "TOTAL=" + list.size)
        }
    }
}
