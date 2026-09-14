package com.neboer.ecode

import okhttp3.OkHttpClient

/**
 * 全应用共用的 OkHttp 实例。存在的唯一理由是**共用同一个 cookie 罐**:
 * [WebViewCookieJar] 把 OkHttp 的 cookie 读写全部委托给 WebView 的 CookieManager,
 * 于是"网页登录、静默换票、二维码、余额"看到的是同一份会话。
 *
 * 之前这份实例是在 MainActivity 里现建的,别的页面要用只能再建一个 —— 迁到单例后
 * 各页拿到的仍是同一份 cookie,不会有第二份会话状态。
 */
object AppHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .build()
    }
}
