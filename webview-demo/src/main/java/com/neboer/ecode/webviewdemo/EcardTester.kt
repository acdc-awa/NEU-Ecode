package com.neboer.ecode.webviewdemo

import android.util.Log
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 一卡通余额链路测试:刻意不带账密,只靠 WebView 登录后 CookieManager 里的 CASTGC
 * 走 selflogin 静默换票链(CAS 有 TGC 时直接 302 回 selflogin?ticket=ST-xxx),
 * 验证"一个 WebView 会话 + TGC 能否同时喂饱 ecode 二维码和 ecard 余额"。
 *
 * 流程移植自主 app EcardClient(见 .har/ecardlogin.har 分析):
 * GET selflogin → 302 CAS(带CASTGC) → 302 selflogin?ticket → 200 表单页
 * → 代浏览器 POST /selfsearch/SSOLogin.aspx → 302 Index.aspx → Home.aspx 解析余额
 */
class EcardTester(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "EcardTester"
        private const val HOME_URL = "http://ecard.neu.edu.cn/selfsearch/User/Home.aspx"
        private const val SELFLOGIN_URL = "http://ecard.neu.edu.cn/selflogin/login.aspx"
        private const val ECARD_HOST = "ecard.neu.edu.cn"
        private const val MAX_REDIRECTS = 10
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        // Home.aspx 中余额形如 <span>主钱包余额：10.89元</span>,余额可能为负
        private val BALANCE_REGEX = Regex("主钱包余额[：:]\\s*(-?[0-9.]+)\\s*元")
    }

    private enum class Outcome { SESSION_OK, SSO_PENDING, CAS_LOGIN_FORM, FAILED }

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val trace = StringBuilder()

    /** 主入口:返回给界面展示的文本(含流程轨迹)。不抛异常。 */
    fun fetchBalance(): String {
        trace.clear()
        trace.append("① 现有会话直接访问 Home.aspx\n")
        parseBalance(getBody(HOME_URL))?.let { return done("✓ 主钱包余额 $it 元(现有会话直接可用)") }

        trace.append("② 无会话,走 selflogin 静默换票链(依赖 WebView 拦截到的 CASTGC,不带账密)\n")
        when (walkSelfloginOnce()) {
            Outcome.SESSION_OK -> {}
            Outcome.SSO_PENDING -> {
                trace.append("③ SSO POST 下发的是预会话(被弹回登录页),带预会话再走一轮\n")
                if (walkSelfloginOnce() != Outcome.SESSION_OK) {
                    return done("✗ 两轮 selflogin 都未建立会话")
                }
            }
            Outcome.CAS_LOGIN_FORM -> return done("✗ CAS 落到登录表单页:CASTGC 未生效,静默换票失败")
            Outcome.FAILED -> return done("✗ selflogin 流程中断(见轨迹)")
        }

        trace.append("④ 会话已建立,重新访问 Home.aspx\n")
        val balance = parseBalance(getBody(HOME_URL))
        return if (balance != null) {
            done("✓ 主钱包余额 $balance 元")
        } else {
            done("✗ 会话链路走完但未解析到余额(见上方HTTP轨迹:5xx=内网站点校外不可达;302弹回登录页=会话未真正建立)")
        }
    }

    private fun done(result: String): String = "【$result】\n$trace"

    private fun traceLine(msg: String) {
        Log.d(TAG, msg)
        trace.append("   $msg\n")
    }

    private fun walkSelfloginOnce(): Outcome {
        var url = SELFLOGIN_URL
        for (i in 0 until MAX_REDIRECTS) {
            traceLine("selfloginWalk[$i]: $url")
            val response = noRedirectClient.newCall(
                Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            ).execute()

            if (response.code in 300..399) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                if (next == null) {
                    traceLine("重定向缺少Location,停止")
                    return Outcome.FAILED
                }
                url = next.toString()
                continue
            }

            val landed = response.request.url
            val html = response.body?.string()
            if (landed.host != ECARD_HOST) {
                traceLine("落地 $landed(HTTP ${response.code}),未到ecard → CAS登录表单")
                return Outcome.CAS_LOGIN_FORM
            }
            val form = parseSsoForm(html, landed)
            if (form == null) {
                if (landed.queryParameter("ticket") != null) {
                    traceLine("ticket落地页未找到SSOLogin表单, HTML前300字: ${html?.take(300)}")
                    return Outcome.FAILED
                }
                traceLine("落地 $landed 无SSO表单 → 视为已有会话; HTML前300字: ${html?.take(300) ?: "(空)"}")
                if (html?.contains("webvpn", ignoreCase = true) == true) {
                    traceLine("⚠ 页面含 webvpn 字样:该响应疑似校外边界/网关页,而非ecard真实页面")
                }
                if (landed.encodedPath.endsWith("/selflogin/login.aspx")) {
                    traceLine("⚠ selflogin 未发生重定向(预期应 302 去CAS换票),该 200 响应可疑")
                }
                return Outcome.SESSION_OK
            }
            val (postUrl, fields) = form
            return submitSsoForm(landed, postUrl, fields)
        }
        traceLine("重定向超过${MAX_REDIRECTS}跳仍未落地")
        return Outcome.FAILED
    }

    /** 从落地页解析 SSOLogin 自动提交表单,返回 (提交地址, 隐藏字段列表);非SSO表单返回 null */
    private fun parseSsoForm(html: String?, pageUrl: HttpUrl): Pair<HttpUrl, List<Pair<String, String>>>? {
        if (html.isNullOrEmpty()) return null
        val doc = Jsoup.parse(html, pageUrl.toString())
        val form = doc.selectFirst("form") ?: return null
        val postUrl = pageUrl.resolve(form.attr("action")) ?: return null
        if (!postUrl.encodedPath.contains("SSOLogin")) return null
        val fields = form.select("input[name]")
            .map { el -> el.attr("name") to el.attr("value") }
        return postUrl to fields
    }

    /**
     * 代浏览器提交 SSO 表单。POST 单独发(便于观察下发的是预会话还是正式会话 cookie),
     * 之后的重定向交给跟随客户端走完。同名 .ASPXAUTSSM 两条 Set-Cookie 由
     * WebViewCookieJar(CookieManager 覆盖语义)天然处理。
     */
    private fun submitSsoForm(pageUrl: HttpUrl, postUrl: HttpUrl, fields: List<Pair<String, String>>): Outcome {
        traceLine("提交SSO表单: $postUrl fields=${fields.joinToString { it.first }}")
        val formBody = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val postResponse = noRedirectClient.newCall(
            Request.Builder().url(postUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", pageUrl.toString())
                .post(formBody)
                .build()
        ).execute()
        val code = postResponse.code
        val setCookies = postResponse.headers("Set-Cookie")
            .map { h -> h.substringBefore(';').trim() }
        val location = postResponse.header("Location")
        postResponse.close()
        traceLine("POST → HTTP $code, Set-Cookie(name部分)=$setCookies")

        if (code !in 300..399) {
            traceLine("SSO表单POST未按预期重定向")
            return Outcome.FAILED
        }
        val next = location?.let { postUrl.resolve(it) } ?: return Outcome.FAILED
        val landed = client.newCall(
            Request.Builder().url(next).header("User-Agent", USER_AGENT).get().build()
        ).execute().use { resp ->
            resp.body?.string()
            resp.request.url
        }
        traceLine("SSO提交后落地: $landed")
        return classifyLanding(landed)
    }

    /** 正式会话 → Index.aspx;预会话 → 被弹回 /selfsearch/login.aspx(注意别和 /selflogin/login.aspx 混淆) */
    private fun classifyLanding(landed: HttpUrl): Outcome {
        if (landed.host != ECARD_HOST) return Outcome.FAILED
        val path = landed.encodedPath.lowercase()
        return if (path.endsWith("/login.aspx") && !path.startsWith("/selflogin")) {
            Outcome.SSO_PENDING
        } else {
            Outcome.SESSION_OK
        }
    }

    private fun getBody(url: String): String? {
        var result: String? = null
        var lastCode = -1
        for (attempt in 1..3) {
            try {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
                ).execute().use { resp ->
                    lastCode = resp.code
                    result = resp.body?.string()
                    traceLine("GET $url → HTTP ${resp.code}, ${result?.length ?: 0}字${if (attempt > 1) "(第${attempt}次尝试)" else ""}")
                }
                if (lastCode in 500..599 && attempt < 3) {
                    traceLine("5xx 服务端错误,1秒后重试…")
                    Thread.sleep(1000)
                    continue
                }
                if (lastCode in 500..599) {
                    traceLine("⚠ 持续 5xx:ecard 是内网站点且无 webvpn 路由,校外直连不可达;校内则可能是服务端临时故障")
                }
                return result
            } catch (e: Exception) {
                traceLine("GET $url 异常: ${e.message}")
                return null
            }
        }
        return result
    }

    private fun parseBalance(html: String?): String? {
        val balance = html?.let { BALANCE_REGEX.find(it)?.groupValues?.get(1) }
        if (balance == null && html != null) {
            traceLine("页面中未匹配到主钱包余额 (len=${html.length})")
        }
        return balance
    }
}
