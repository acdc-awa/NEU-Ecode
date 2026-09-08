package com.neboer.ecode

import android.util.Log
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 一卡通(ecard.neu.edu.cn)自助查询客户端,获取校园卡主钱包余额。
 *
 * 真实登录流程(见 .har/ecardlogin.har,缺一步会话就建立不起来):
 * 1. GET selflogin/login.aspx → 302 → CAS tpass/login?service=selflogin
 * 2. CAS 有 TGC 时直接 302 回 selflogin?ticket=ST-xxx;TGC 失效则返回登录表单
 * 3. selflogin?ticket 返回 200 表单页,内含服务端生成的隐藏字段(username/timestamp/auid),
 *    浏览器 JS 会自动 POST /selfsearch/SSOLogin.aspx → 302 Index.aspx,
 *    该 POST 的响应才下发真正的 .ASPXAUTSSM 会话 cookie
 * 因此建立会话 = 手动跟随重定向 + 在 ticket 落地页解析表单并代为提交。
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
        if (establishViaSelflogin()) return parseBalance(getBody(HOME_URL))
        Log.w(TAG, "selflogin未建立会话,判定CAS会话失效,先用存储凭据重新登录刷新TGC")
        val username = credentialManager.getUsername()
        val password = credentialManager.getPassword()
        if (username == null || password == null || !casAuthenticator.login(username, password)) {
            Log.w(TAG, "CAS重新登录失败,本次放弃余额获取")
            return null
        }
        if (!establishViaSelflogin()) {
            Log.w(TAG, "CAS登录后selflogin仍未建立ecard会话")
            return null
        }
        return parseBalance(getBody(HOME_URL))
    }

    /**
     * 手动跟随 selflogin 的重定向链(最多 MAX_REDIRECTS 跳),并在 ticket 落地页
     * 解析自动提交表单代浏览器 POST 给 SSOLogin.aspx 以换取 .ASPXAUTSSM 会话。
     * 返回 true 表示会话已建立;落在 CAS 登录表单(pass.neu.edu.cn)说明 TGC 失效。
     */
    private fun establishViaSelflogin(): Boolean {
        var url = SELFLOGIN_URL
        for (i in 0 until MAX_REDIRECTS) {
            Log.d(TAG, "selfloginWalk[$i]: $url")
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

            val landed = response.request.url
            val code = response.code
            if (landed.host != ECARD_HOST) {
                response.close()
                Log.d(TAG, "落地在 $landed (HTTP $code),未到ecard,判定CAS会话失效")
                return false
            }

            val html = response.body?.string()
            val form = parseSsoForm(html, landed)
            if (form == null) {
                if (landed.queryParameter("ticket") != null) {
                    Log.w(TAG, "ticket落地页未找到SSOLogin表单, HTML前500字: ${html?.take(500)}")
                    return false
                }
                Log.d(TAG, "落地 $landed 无SSO表单,视为已有会话")
                return true
            }
            val (postUrl, fields) = form
            return submitSsoForm(landed, postUrl, fields)
        }
        Log.w(TAG, "selflogin重定向超过${MAX_REDIRECTS}跳仍未落地")
        return false
    }

    /** 从落地页解析 SSOLogin 自动提交表单,返回 (提交地址, 隐藏字段列表);非SSO表单返回 null */
    private fun parseSsoForm(
        html: String?,
        pageUrl: HttpUrl
    ): Pair<HttpUrl, List<Pair<String, String>>>? {
        if (html.isNullOrEmpty()) return null
        val doc = Jsoup.parse(html, pageUrl.toString())
        val form = doc.selectFirst("form") ?: return null
        val postUrl = pageUrl.resolve(form.attr("action")) ?: return null
        if (!postUrl.encodedPath.contains("SSOLogin")) return null
        val fields = form.select("input[name]")
            .map { el -> el.attr("name") to el.attr("value") }
        return postUrl to fields
    }

    /** 代浏览器提交 SSO 表单;followClient 会自动跟随 302 到 Index.aspx,会话 cookie 由 cookieJar 捕获 */
    private fun submitSsoForm(
        pageUrl: HttpUrl,
        postUrl: HttpUrl,
        fields: List<Pair<String, String>>
    ): Boolean {
        Log.d(TAG, "提交SSO表单: $postUrl fields=${fields.joinToString { it.first }}")
        val formBody = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", pageUrl.toString())
            .post(formBody)
            .build()
        val response = followClient.newCall(request).execute()
        val code = response.code
        val landed = response.request.url
        response.close()
        Log.d(TAG, "SSO表单提交 → HTTP $code, 落地: $landed")
        return landed.host == ECARD_HOST
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
