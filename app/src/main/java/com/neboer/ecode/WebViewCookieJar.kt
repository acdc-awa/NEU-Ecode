package com.neboer.ecode

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp CookieJar 直接委托给 WebView 的 CookieManager,让 OkHttp 请求与 WebView 登录共享同一份 cookie 存储:
 * - loadForRequest:请求哪个域就带哪个域的 cookie(重定向到 CAS 自动带上 CASTGC,重定向到 webvpn 自动带 webvpn 会话)
 * - saveFromResponse:响应种下的新 cookie 回写 CookieManager,会话始终只有一份
 *
 * 取代 PersistentCookieJar:CookieManager 同名 cookie 按"后写覆盖先写"存储,
 * 天然规避了 ecard SSOLogin 下发两条同名 .ASPXAUTSSM(先空值删旧再写正式值)的污染问题;
 * 且 CookieManager 随 WebView 登录持久化,应用重装前会话一直可用。
 */
class WebViewCookieJar : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        for (cookie in cookies) {
            manager.setCookie(url.toString(), cookie.toString())
        }
        manager.flush()
    }

    companion object {
        private const val ECODE_ORIGIN = "https://ecode.neu.edu.cn"
        /** SESSION 的 path 是 /ecode/api,只有该路径下的 URL 才读得到它 */
        private const val ECODE_API_URL = "$ECODE_ORIGIN/ecode/api/qr-code"
        /** CASTGC 挂在 /tpass/ 下 */
        private const val CAS_TGC_URL = "https://pass.neu.edu.cn/tpass/"

        /** 清空全部会话(切换账号/认证彻底失效时用) */
        fun clearAll() {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }

        /**
         * 只清 ecode 域的会话 cookie(SESSION 与 XSRF-TOKEN),保留 CASTGC 与门户的
         * CK_LC/CK_VL/SESS_ID。
         *
         * ecode 会话失效时应该用这个而不是 [clearAll]:"二维码会话过期"不等于"所有子系统都过期",
         * 而 clearAll 会连带作废 2 小时寿命的 CASTGC 和门户凭据——后者本来还能用,却被迫重新登录。
         */
        fun clearEcodeSession() {
            val manager = CookieManager.getInstance()
            // 同一个 cookie 的 host-only 变体与 domain 变体在 CookieManager 里是两条记录,
            // 而抓包只看得到请求侧的 domain 字段、分不清属于哪种,所以两种都删一遍
            for (domain in listOf("", "; Domain=ecode.neu.edu.cn")) {
                manager.setCookie(ECODE_API_URL, "SESSION=; Path=/ecode/api; Max-Age=0$domain")
                manager.setCookie("$ECODE_ORIGIN/", "XSRF-TOKEN=; Path=/; Max-Age=0$domain")
            }
            manager.flush()
        }

        /**
         * ecode 会话是否存在 = ecode API 路径下是否带着 HttpOnly 的 SESSION cookie。
         *
         * 不能用 XSRF-TOKEN 判定(2026-09-10 实测):它"缺了就发",对完全匿名的请求也会下发,
         * 而 OkHttp 对 401 响应同样会回写 Set-Cookie —— 于是我们自己的失败请求会不停把它种回
         * CookieManager,拿它当登录标记会长期为真。SESSION 只在 ecode 的登录/兑票流程中下发,
         * 匿名请求不会凭空得到它。
         */
        fun hasEcodeSession(): Boolean {
            return CookieManager.getInstance()
                .getCookie(ECODE_API_URL)
                ?.split(";")
                ?.any { it.trim().startsWith("SESSION=", ignoreCase = true) } == true
        }

        /**
         * CAS 的 TGT 凭据是否还在。CASTGC 只在 WebView 输账密登录时签发(Max-Age=7200,2 小时),
         * 之后兑票不会续期,所以它一旦消失就只能重新登录——门户余额正是卡在这里。
         * 该 cookie 不是 HttpOnly,可以从 CookieManager 读到。
         */
        fun hasCastgc(): Boolean {
            return CookieManager.getInstance()
                .getCookie(CAS_TGC_URL)
                ?.split(";")
                ?.any { it.trim().startsWith("CASTGC=", ignoreCase = true) } == true
        }
    }
}
