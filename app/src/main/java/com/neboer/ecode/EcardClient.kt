package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 一卡通(ecard.neu.edu.cn)自助查询客户端,获取校园卡主钱包余额。
 *
 * selfsearch 页面依赖 .ASPXAUTSSM 会话 cookie,该 cookie 由 selflogin 入口
 * 消费 CAS ticket 后下发。建立会话的方式是手动跟随 selflogin 的重定向链:
 * 其 302 会指向 CAS 并带上真实的 service 参数,TGC 有效时 CAS 直接签发
 * ticket 并回到 selflogin 消费,全程无需重新提交账密。
 */
class EcardClient(
    private val client: OkHttpClient,
    private val credentialManager: CredentialManager,
    private val casAuthenticator: CasAuthenticator
) {
    companion object {
        private const val TAG = "EcardClient"
        private const val HOME_URL = "http://ecard.neu.edu.cn/selfsearch/User/Home.aspx"
        private const val SELFLOGIN_URL = "http://ecard.neu.edu.cn/selflogin/login.aspx"
        private const val ECARD_HOST = "ecard.neu.edu.cn"
        private const val MAX_REDIRECTS = 10
        // Home.aspx 中余额形如 <span>主钱包余额：10.89元</span>,余额可能为负
        private val BALANCE_REGEX = Regex("主钱包余额[：:]\\s*(-?[0-9.]+)\\s*元")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val followClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 返回主钱包余额(如 "10.89"),失败返回 null。不抛异常,不改变凭据/二维码流程的生命周期。 */
    fun fetchBalance(): String? {
        return try {
            parseBalance(getBody(HOME_URL)) ?: establishSessionAndFetch()
        } catch (e: Exception) {
            Log.e(TAG, "余额获取异常", e)
            null
        }
    }

    private fun establishSessionAndFetch(): String? {
        Log.i(TAG, "ecard会话无效,走selflogin建立会话")
        if (!walkSelflogin()) {
            Log.w(TAG, "selflogin未落地到ecard,判定CAS会话失效,先用存储凭据重新登录刷新TGC")
            val username = credentialManager.getUsername()
            val password = credentialManager.getPassword()
            if (username == null || password == null || !casAuthenticator.login(username, password)) {
                Log.w(TAG, "CAS重新登录失败,本次放弃余额获取")
                return null
            }
            if (!walkSelflogin()) {
                Log.w(TAG, "CAS登录后selflogin仍未建立ecard会话")
                return null
            }
        }
        return parseBalance(getBody(HOME_URL))
    }

    /**
     * 手动跟随 selflogin 的重定向链(最多 MAX_REDIRECTS 跳)。
     * 返回 true 表示最终落在 ecard.neu.edu.cn 的页面上(会话已建立);
     * 落在 CAS 登录表单(pass.neu.edu.cn)说明 TGC 失效,返回 false。
     */
    private fun walkSelflogin(): Boolean {
        var url = SELFLOGIN_URL
        for (i in 0 until MAX_REDIRECTS) {
            Log.d(TAG, "walkSelflogin[$i]: $url")
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            val response = noRedirectClient.newCall(request).execute()

            if (response.code in 300..399) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                if (next == null) {
                    Log.w(TAG, "重定向缺少Location,停止")
                    return false
                }
                url = next.toString()
                continue
            }

            val landedHost = response.request.url.host
            val code = response.code
            response.close()
            Log.d(TAG, "walkSelflogin落地: $landedHost (HTTP $code)")
            return landedHost == ECARD_HOST
        }
        Log.w(TAG, "selflogin重定向超过${MAX_REDIRECTS}跳仍未落地")
        return false
    }

    private fun getBody(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val response = followClient.newCall(request).execute()
        val body = response.body?.string()
        Log.d(TAG, "GET $url → HTTP ${response.code}, ${body?.length ?: 0}字")
        return body
    }

    private fun parseBalance(html: String?): String? {
        val balance = html?.let { BALANCE_REGEX.find(it)?.groupValues?.get(1) }
        if (balance == null && html != null) {
            Log.w(TAG, "页面中未匹配到主钱包余额 (len=${html.length})")
        }
        return balance
    }
}
