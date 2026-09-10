package com.neboer.ecode

import android.content.Context
import android.util.Log
import android.webkit.WebSettings

/**
 * OkHttp 侧统一使用的 User-Agent,目的是别让服务端把我们看成陌生客户端。
 *
 * 起因(2026-09-10 学校登录日志):同一次应用登录会产生两条认证记录——
 *   Android  Chrome Mobile  https://ecode.neu.edu.cn/ecode/api/sso/login      (WebView 的登录)
 *   Android  unknown        https://personal.neu.edu.cn/portal/.../cas_login/1 (我们的 OkHttp)
 * 因为网络层原先发的 UA 是截断串 "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
 * 没有 Chrome/xxx 段,任何浏览器识别都只能得到 unknown。而"未被识别的客户端"正是风控
 * 最容易区别对待的对象,对于一个靠屏幕抓取吃饭的应用没有半点好处。
 *
 * 所以改成完整的 Chrome Mobile 串。WebView 一旦被创建([adoptWebViewUserAgent]),
 * 就直接沿用系统 WebView 的默认 UA,让两边指纹完全一致;没来得及调用时用 [DEFAULT],
 * 同样是可识别的正常浏览器串,不会再退化成 unknown。
 */
object UserAgent {

    private const val TAG = "UserAgent"

    /** 兜底 UA:完整的 Chrome Mobile 串(注意必须带 Chrome/xxx,否则又会被判成 unknown) */
    private const val DEFAULT =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var value: String = DEFAULT

    /**
     * 可选:在已创建 WebView 的页面(主线程)调用,把 UA 对齐成系统 WebView 的默认值,
     * 使 OkHttp 与 WebView 在服务端眼里是同一个客户端。失败则保持 [DEFAULT]。
     */
    fun adoptWebViewUserAgent(context: Context) {
        val ua = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (ua == null) {
            Log.w(TAG, "读取 WebView 默认 UA 失败,继续使用兜底 UA")
            return
        }
        if (ua == value) return
        value = ua
        Log.i(TAG, "UA 已对齐系统 WebView: $ua")
    }

    /** 请求头用值(任何线程可读) */
    val current: String get() = value
}
