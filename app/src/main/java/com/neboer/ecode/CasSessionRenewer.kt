package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * CAS 会话静默续期:不带账密,只靠 CookieManager 里的 CASTGC(WebView 登录后长期有效)
 * 重走 service=ecode 的兑票链,刷新 ecode 的 XSRF-TOKEN 应用会话。
 * CASTGC 失效时返回 false,由 UI 层引导用户重新走 WebView 登录。
 */
class CasSessionRenewer(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "CasSessionRenewer"
        private const val CAS_LOGIN_URL =
            "https://pass.neu.edu.cn/tpass/login?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"
        private const val ECODE_HOST = "ecode.neu.edu.cn"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    private val followClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** true = ecode 会话已刷新(新 XSRF-TOKEN 已入 CookieManager);false = CASTGC 失效,需重新登录 */
    fun renewEcodeSession(): Boolean {
        val response = followClient.newCall(
            Request.Builder().url(CAS_LOGIN_URL).header("User-Agent", USER_AGENT).get().build()
        ).execute()
        val landed = response.request.url
        val code = response.code
        response.close()
        Log.d(TAG, "静默续期: HTTP $code 落地 $landed")
        val ok = landed.host == ECODE_HOST && WebViewCookieJar.hasEcodeSession()
        if (!ok) Log.w(TAG, "静默续期失败:未落回ecode或XSRF-TOKEN缺失,CASTGC 已失效")
        return ok
    }
}
