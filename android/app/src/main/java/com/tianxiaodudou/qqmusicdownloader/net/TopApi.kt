package com.tianxiaodudou.qqmusicdownloader.net

import org.json.JSONObject

/** 排行榜：分类列表 + 榜单歌曲。 */
object TopApi {

    data class Chart(val id: Int, val name: String, val count: Int)

    fun charts(): List<Chart> {
        val data = QqClient.cgi("music.musicToplist.Toplist", "GetAll", JSONObject())
        val out = ArrayList<Chart>()
        // 现在的返回是 data.group[].toplist[]；老接口是 data.toplist[]，两种都认。
        val groups = data.optJSONArray("group")
        if (groups != null) {
            for (g in 0 until groups.length()) {
                appendCharts(groups.optJSONObject(g)?.optJSONArray("toplist"), out)
            }
        }
        if (out.isEmpty()) {
            appendCharts(data.optJSONArray("toplist") ?: data.optJSONArray("v_list"), out)
        }
        return out
    }

    private fun appendCharts(arr: org.json.JSONArray?, out: MutableList<Chart>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("topId", o.optInt("topID", 0))
            if (id <= 0) continue
            val name = o.optString("topName").ifEmpty { o.optString("title") }
            if (name.isEmpty()) continue
            if (out.any { it.id == id }) continue
            out.add(
                Chart(
                    id = id,
                    name = name,
                    count = o.optInt("totalNum", o.optInt("songNum", 0)),
                )
            )
        }
    }

    fun songs(chart: Chart, page: Int = 1, num: Int = 100): List<MusicApi.Song> {
        val p = JSONObject()
        p.put("topId", chart.id)
        p.put("offset", num * (page - 1))
        p.put("num", num)
        p.put("withTags", true)
        val data = QqClient.cgi("music.musicToplist.Toplist", "GetDetail", p)
        val arr = data.optJSONArray("songInfoList") ?: data.optJSONArray("songlist") ?: return emptyList()
        val out = ArrayList<MusicApi.Song>()
        for (i in 0 until arr.length()) MusicApi.parseSong(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }
}
