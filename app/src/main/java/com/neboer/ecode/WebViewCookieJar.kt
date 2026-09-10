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
        /** 清空全部会话(切换账号/认证彻底失效时用) */
        fun clearAll() {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }

        /** ecode 应用会话是否存在(WebView 登录或静默续期成功的标志) */
        fun hasEcodeSession(): Boolean {
            return CookieManager.getInstance()
                .getCookie("https://ecode.neu.edu.cn/")
                ?.split(";")
                ?.any { it.trim().startsWith("XSRF-TOKEN=", ignoreCase = true) } == true
        }

        /**
         * CAS 的 TGT 凭据是否还在。CASTGC 只在 WebView 输账密登录时签发(Max-Age=7200,2 小时),
         * 之后兑票不会续期,所以它一旦消失就只能重新登录——门户余额正是卡在这里。
         * 该 cookie 不是 HttpOnly,可以从 CookieManager 读到。
         */
        fun hasCastgc(): Boolean {
            return CookieManager.getInstance()
                .getCookie("https://pass.neu.edu.cn/tpass/")
                ?.split(";")
                ?.any { it.trim().startsWith("CASTGC=", ignoreCase = true) } == true
        }
    }
}
