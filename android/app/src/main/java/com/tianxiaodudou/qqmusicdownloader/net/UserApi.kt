package com.tianxiaodudou.qqmusicdownloader.net

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 账号资料：头像 / 昵称 / 会员类型 / 会员等级 / 剩余天数 / 听歌等级。 */
object UserApi {

    @Volatile
    private var cache: Profile? = null

    @Volatile
    private var cachedAt = 0L

    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    data class Profile(
        val nickname: String,
        val avatar: String,
        val vipLabel: String,
        val vipLevel: Int,
        val remainDays: Int,
        val musicLevel: Int,
        val isVip: Boolean,
        /** 普通会员（绿钻）到期时间，毫秒；0 表示没有。 */
        val normalVipEnd: Long = 0L,
        /** 超级会员（豪华绿钻）到期时间，毫秒；0 表示没有。 */
        val superVipEnd: Long = 0L,
    )

    fun fetch(context: android.content.Context): Profile? {
        val s = QqClient.session ?: return null
        val now = System.currentTimeMillis()
        cache?.let { if (now - cachedAt < 5 * 60_000L) return it }
        var nickname = s.nickname
        var avatar = ""
        var encryptedUin = s.encryptUin

        // 主页头部：昵称 + 头像（拿不到就用 QQ 头像兜底）
        runCatching {
            val data = QqClient.cgi(
                "music.UnifiedHomepage.UnifiedHomepageSrv",
                "GetHomepageHeader",
                JSONObject().put("uin", s.uin).put("IsQueryTabDetail", 1),
            )
            val base = data.optJSONObject("Info")?.optJSONObject("BaseInfo") ?: data.optJSONObject("BaseInfo")
            if (base != null) {
                val n = base.optString("Name")
                if (n.isNotEmpty()) nickname = n
                avatar = base.optString("Avatar").ifEmpty { base.optString("BigAvatar") }
                val euin = base.optString("EncryptedUin")
                if (euin.isNotEmpty()) encryptedUin = euin
            }
        }
        // 老版本登录态里没有加密 uin，这里补上，收藏歌单才用得了
        if (encryptedUin.isNotEmpty() && encryptedUin != s.encryptUin) {
            runCatching { Session.save(context, s.copy(encryptUin = encryptedUin)) }
        }
        if (avatar.isEmpty()) avatar = "https://q1.qlogo.cn/g?b=qq&nk=" + s.uin + "&s=140"

        var vipLabel = "未开通"
        var vipLevel = 0
        var remainDays = 0
        var musicLevel = 0
        var isVip = false
        var normalVipEnd = 0L
        var superVipEnd = 0L

        runCatching {
            val data = QqClient.cgi("VipLogin.VipLoginInter", "vip_login_base", JSONObject())
            val identity = data.optJSONObject("identity")
            val ui = data.optJSONObject("userinfo")
            musicLevel = ui?.optInt("music_level", 0) ?: 0
            vipLevel = identity?.optInt("level", 0) ?: 0

            val huge = identity?.optInt("HugeVip", 0) ?: 0
            val hugeYear = identity?.optInt("HugeYearFlag", 0) ?: 0
            val green = identity?.optInt("vip", 0) ?: 0
            val year = identity?.optInt("yearflag", 0) ?: 0
            val svip = data.optInt("svip", 0)
            isVip = svip == 1 || huge == 1 || green == 1
            vipLabel = when {
                svip == 1 -> "超级会员"
                huge == 1 -> if (hugeYear == 1) "豪华绿钻·年费" else "豪华绿钻"
                green == 1 -> if (year == 1) "绿钻·年费" else "绿钻"
                else -> "未开通"
            }

            val expire = ui?.optLong("expire", 0L) ?: 0L
            if (expire > 1_000_000_000L) normalVipEnd = expire * 1000L
            else if (expire > 0L) normalVipEnd = System.currentTimeMillis() + expire * 1000L
            // 豪华绿钻（超级会员）到期时间，常见为 "2026-01-01" 或时间戳字符串
            val hugeEnd = identity?.optString("HugeVipEnd").orEmpty()
            if (hugeEnd.isNotEmpty()) {
                superVipEnd = parseDateMs(hugeEnd)
            }
            val endMs = maxOf(normalVipEnd, superVipEnd)
            if (endMs > 0) {
                remainDays = TimeUnit.MILLISECONDS.toDays(endMs - System.currentTimeMillis()).toInt().coerceAtLeast(0)
            }
        }

        val result = Profile(
            nickname, avatar, vipLabel, vipLevel, remainDays, musicLevel, isVip,
            normalVipEnd = normalVipEnd,
            superVipEnd = superVipEnd,
        )
        cache = result
        cachedAt = now
        return result
    }

    private fun parseDateMs(raw: String): Long {
        val v = raw.trim()
        val n = v.toLongOrNull()
        if (n != null && n > 0) {
            return if (n > 1_000_000_000L) n * 1000L else n
        }
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(v)?.time ?: 0L
        }.getOrDefault(0L)
    }

    fun avatarOf(uin: String, size: Int = 140): String =
        "https://q1.qlogo.cn/g?b=qq&nk=" + uin + "&s=" + size
}
