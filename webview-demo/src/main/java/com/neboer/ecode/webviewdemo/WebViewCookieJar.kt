package com.neboer.ecode.webviewdemo

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp CookieJar 直接委托给 WebView 的 CookieManager,让 OkHttp 请求与 WebView 登录共享同一份 cookie 存储:
 * - loadForRequest:请求哪个域就带哪个域的 cookie(重定向到 CAS 自动带上 CASTGC,重定向到 webvpn 自动带 webvpn 会话)
 * - saveFromResponse:响应种下的新 cookie 回写 CookieManager,会话始终只有一份
 *
 * CookieManager 同名 cookie 按"后写覆盖先写"存储,天然规避了 ecard SSOLogin
 * 下发两条同名 .ASPXAUTSSM(先空值删旧再写正式值)的 CookieJar 污染问题。
 */
class WebViewCookieJar : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            CookieManager.getInstance().setCookie(url.toString(), cookie.toString())
        }
        CookieManager.getInstance().flush()
    }
}
