package com.tianxiaodudou.qqmusicdownloader.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 歌词：优先取 QRC 逐字歌词（hex -> 3DES -> zlib -> XML），拿不到就退回 LRC 逐行；
 * 同时取翻译（trans）与音译（roma）。
 */
object LyricApi {

    data class Word(val text: String, val startMs: Int, val durMs: Int)

    data class Line(
        val timeMs: Int,
        val text: String,
        val words: List<Word>,
        val translation: String = "",
        val roma: String = "",
    )

    /** 歌词来源，用于「歌曲信息」里展示。 */
    data class Result(val lines: List<Line>, val source: String)

    private const val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL"

    fun fetch(mid: String): List<Line> = load(mid).lines

    fun load(mid: String): Result {
        val p = JSONObject()
        p.put("songMid", mid)
        p.put("songId", 0)
        p.put("lrc_t", 0)
        p.put("qrc", 1)
        p.put("qrc_t", 0)
        p.put("roma", 1)
        p.put("roma_t", 0)
        p.put("trans", 1)
        p.put("trans_t", 0)
        p.put("needSingingAnnotations", false)
        p.put("type", 0)
        val data = runCatching {
            QqClient.cgi("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", p)
        }.getOrNull() ?: return Result(emptyList(), "无")

        val lyric = data.optString("lyric").orEmpty()
        val lrc = data.optString("lrc").orEmpty()
        val trans = data.optString("trans").orEmpty()
        val roma = data.optString("roma").orEmpty()
        val qrcXml = qrcXmlOf(lyric)
        // QRC 是 QQ 自己的 3DES 变体加密后的 XML；解不开时用同一接口下发的 LRC 兜底。
        val lines: List<Line>
        val source: String
        if (qrcXml != null) {
            lines = parseQrc(qrcXml)
            source = "QRC 逐字"
        } else {
            lines = parseLrc(decodeLrc(lrc.ifEmpty { lyric }))
            source = if (lines.isEmpty()) "无" else "LRC 逐行"
        }
        if (lines.isEmpty()) return Result(lines, source)
        val tr = parseLrc(decodeLrc(trans))
        val rm = parseLrc(decodeLrc(roma))
        return Result(attachAll(lines, tr, rm), source)
    }

    /** 上游把 QRC 的 xml 塞在 lyric 里（加密），先解密再判断。 */
    private fun qrcXmlOf(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.contains("<QrcInfos")) return raw
        val plain = decryptQrc(raw) ?: return null
        return if (plain.contains("<QrcInfos")) plain else null
    }

    fun decryptQrc(hex: String): String? = TripleDes.qrcDecrypt(hex)

    private fun inflate(src: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(src)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun decodeLrc(raw: String): String {
        if (raw.isEmpty()) return ""
        val text = runCatching {
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
        return if (text != null && text.contains("]")) text else raw
    }

    private fun parseLrc(lrc: String): List<Line> {
        if (lrc.isEmpty()) return emptyList()
        val out = ArrayList<Line>()
        for (row in lrc.split("\n")) {
            val m = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?](.*)""").find(row) ?: continue
            val min = m.groupValues[1].toIntOrNull() ?: continue
            val sec = m.groupValues[2].toIntOrNull() ?: continue
            val frac = m.groupValues[3]
            val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            out.add(Line(min * 60000 + sec * 1000 + ms, text, emptyList()))
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun parseQrc(xml: String): List<Line> {
        val out = ArrayList<Line>()
        // 先取 LyricContent 属性值并反转义实体，否则歌词里的 &apos; / &#10; 会原样显示。
        var body = xml
        val at = body.indexOf("LyricContent=\"")
        if (at >= 0) {
            body = body.substring(at + 14)
            val end = body.indexOf("\"/>")
            if (end > 0) body = body.substring(0, end)
        }
        body = body
            .replace("&#10;", "\n").replace("&#xA;", "\n").replace("&#xa;", "\n")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val lineRe = Regex("""\[(\d+),(\d+)\]([^\r\n]*)""")
        val wordRe = Regex("""([^()]*)\((\d+),(\d+)\)""")
        for (m in lineRe.findAll(body)) {
            val lineStart = m.groupValues[1].toIntOrNull() ?: continue
            val row = m.groupValues[3]
            val words = wordRe.findAll(row).mapNotNull { w ->
                val t = w.groupValues[1]
                val st = w.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val d = w.groupValues[3].toIntOrNull() ?: 0
                if (t.isEmpty()) null else Word(t, st, d)
            }.toList()
            val plain = words.joinToString("") { it.text }.trim()
            if (plain.isEmpty()) continue
            val relative = words.isNotEmpty() && words.first().startMs < lineStart
            val fixed = if (relative) words.map { it.copy(startMs = it.startMs + lineStart) } else words
            out.add(Line(lineStart, plain, fixed))
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun attachAll(lines: List<Line>, trans: List<Line>, roma: List<Line>): List<Line> {
        if (trans.isEmpty() && roma.isEmpty()) return lines
        return lines.map { l ->
            val t = nearest(trans, l.timeMs)
            val r = nearest(roma, l.timeMs)
            l.copy(translation = t, roma = r)
        }
    }

    private fun nearest(list: List<Line>, timeMs: Int): String {
        if (list.isEmpty()) return ""
        val hit = list.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) } ?: return ""
        return if (kotlin.math.abs(hit.timeMs - timeMs) <= 600) hit.text else ""
    }
}
