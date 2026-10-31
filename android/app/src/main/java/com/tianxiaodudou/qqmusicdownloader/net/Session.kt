package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context

/** QQ 音乐登录凭证：uin 是 musicid，musickey 既是 cookie 也是 g_tk 的来源。 */
data class Session(
    val uin: String,
    val musickey: String,
    val nickname: String,
    /** 登录响应里的加密 uin，收藏歌单等接口要用；老账号没有就回退明文 uin。 */
    val encryptUin: String = "",
    val refreshKey: String = "",
    val refreshToken: String = "",
    val accessToken: String = "",
    val openid: String = "",
    val unionid: String = "",
    val loginType: Int = 1,
    val expireAt: Long = 0L,
) {

    companion object {
        private const val PREFS = "qy_session"
        private const val K_UIN = "uin"
        private const val K_KEY = "musickey"
        private const val K_NICK = "nickname"
        private const val K_EUIN = "encrypt_uin"
        private const val K_RKEY = "refresh_key"
        private const val K_RTOKEN = "refresh_token"
        private const val K_ATOKEN = "access_token"
        private const val K_OPENID = "openid"
        private const val K_UNIONID = "unionid"
        private const val K_LTYPE = "login_type"
        private const val K_EXPIRE = "expire_at"

        fun load(ctx: Context): Session? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val uin = p.getString(K_UIN, null) ?: return null
            val key = p.getString(K_KEY, null) ?: return null
            if (key.isEmpty()) return null
            return Session(
                uin = uin,
                musickey = key,
                nickname = p.getString(K_NICK, "").orEmpty(),
                encryptUin = p.getString(K_EUIN, "").orEmpty(),
                refreshKey = p.getString(K_RKEY, "").orEmpty(),
                refreshToken = p.getString(K_RTOKEN, "").orEmpty(),
                accessToken = p.getString(K_ATOKEN, "").orEmpty(),
                openid = p.getString(K_OPENID, "").orEmpty(),
                unionid = p.getString(K_UNIONID, "").orEmpty(),
                loginType = p.getInt(K_LTYPE, 1),
                expireAt = p.getLong(K_EXPIRE, 0L),
            )
        }

        fun save(ctx: Context, s: Session) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_UIN, s.uin)
                .putString(K_KEY, s.musickey)
                .putString(K_NICK, s.nickname)
                .putString(K_EUIN, s.encryptUin)
                .putString(K_RKEY, s.refreshKey)
                .putString(K_RTOKEN, s.refreshToken)
                .putString(K_ATOKEN, s.accessToken)
                .putString(K_OPENID, s.openid)
                .putString(K_UNIONID, s.unionid)
                .putInt(K_LTYPE, s.loginType)
                .putLong(K_EXPIRE, s.expireAt)
                .apply()
            QqClient.session = s
            QqClient.onSessionChanged()
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            QqClient.session = null
            QqClient.onSessionChanged()
        }
    }
}
