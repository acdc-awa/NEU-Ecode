package com.neboer.ecode

import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class CasAuthenticator(
    private val client: OkHttpClient,
    private val credentialManager: CredentialManager
) {
    private val casClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "CasAuth"
        private const val CAS_LOGIN_URL =
            "https://pass.neu.edu.cn/tpass/login?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"
        private const val GUIDE_OK = "__GUIDE_OK__"

        private const val PUBLIC_KEY_BASE64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnjA28DLKXZzxb" +
            "Kmo9/1WkVLf1mr+wtLXLXt6sC4WiBCtsbzF5ewm7ARZeAdS3iZtqlYPn6" +
            "IcUoOw42H8nAK/tfFcIb6dZ1K0atn0U39oWCGPzYuKtLJeMuNZiDXVuA" +
            "XtojrckOjLW9B3gUnaNGLuIx0fYe66l0o9WjU2cGLNZQfiIxs2h00z1EA" +
            "9IdSnVxiVQWSD+lsP3JZXh2TT287la4Y4603SQNKTK/QvXfcmccwTEd1I" +
            "W6HwGxD6QrkInBiHisKWxmveN7UDSaQRZ/J97G0YC32pD38WT53izXeK0" +
            "p/kU/X37VP555um1wVWFvPIuc9I7gMP1+hq5a+X6c++tQIDAQAB"
    }

    private fun rsaEncrypt(data: String): String {
        return try {
            val keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "RSA加密失败", e)
            throw e
        }
    }

    fun login(username: String, password: String): Boolean {
        Log.d(TAG, "=== CAS登录开始 ===")

        Log.d(TAG, "Step1: 获取登录页...")
        val pageParams = fetchLoginPage()
        if (pageParams == null) {
            Log.w(TAG, "Step1 失败: 无法解析登录页")
            return false
        }
        val (action, lt, execution) = pageParams
        Log.d(TAG, "Step1 OK: lt存在, execution=$execution")

        Log.d(TAG, "Step2: 提交账密...")
        val result = submitCredentials(action, lt, execution, username, password)
        when {
            result == null -> {
                Log.w(TAG, "Step2 失败: 未获取到ticket（账密错误或服务器拒绝）")
                return false
            }
            result == GUIDE_OK -> {
                Log.d(TAG, "Step2 OK: TPass引导流程完成，会话已建立")
            }
            else -> {
                Log.d(TAG, "Step2 OK: 获得ticket")
                Log.d(TAG, "Step3: 兑换ticket...")
                if (!redeemTicket(result)) {
                    Log.w(TAG, "Step3 失败: ticket兑换后未找到XSRF-TOKEN")
                    return false
                }
            }
        }

        Log.d(TAG, "=== CAS登录成功 ===")
        return true
    }

    private fun fetchLoginPage(): Triple<String, String, String>? {
        Log.d(TAG, "GET $CAS_LOGIN_URL")
        val request = Request.Builder().url(CAS_LOGIN_URL).get().build()
        val response = casClient.newCall(request).execute()
        Log.d(TAG, "  响应码: ${response.code}")

        val html = response.body?.string()
        if (html == null) {
            Log.w(TAG, "  response.body为空")
            return null
        }

        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form#loginForm")
        if (form == null) {
            Log.w(TAG, "  未找到form#loginForm, 页面标题=${doc.title()}, HTML长度=${html.length}")
            Log.d(TAG, "  页面forms: ${doc.select("form").joinToString { it.id() + "→" + it.attr("action") }}")
            Log.d(TAG, "  HTML前1000字: ${html.take(1000)}")
            return null
        }

        val lt = form.selectFirst("input#lt")?.attr("value")
        val execution = form.selectFirst("input[name=execution]")?.attr("value")
        val action = form.attr("action")

        if (lt == null || execution == null) {
            Log.w(TAG, "  lt=$lt execution=$execution 解析不完整")
            Log.d(TAG, "  form HTML前500字: ${form.html().take(500)}")
            return null
        }

        return Triple(action, lt, execution)
    }

    private fun submitCredentials(
        action: String, lt: String, execution: String,
        username: String, password: String
    ): String? {
        val rsaValue = rsaEncrypt(username + password)

        val formBody = FormBody.Builder()
            .add("rsa", rsaValue)
            .add("ul", username.length.toString())
            .add("pl", password.length.toString())
            .add("lt", lt)
            .add("execution", execution)
            .add("_eventId", "submit")
            .build()

        val postUrl = if (action.startsWith("http")) action else "https://pass.neu.edu.cn$action"
        Log.d(TAG, "  POST $postUrl")

        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(postUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .post(formBody)
            .build()

        val response = noRedirectClient.newCall(request).execute()
        Log.d(TAG, "  响应码: ${response.code}")

        if (response.code in 300..399) {
            val location = response.header("Location")
            if (location != null) {
                val uri = android.net.Uri.parse(location)
                val ticket = uri.getQueryParameter("ticket")
                if (ticket != null) {
                    val isPassHost = uri.host?.contains("pass.neu.edu.cn") == true
                    if (isPassHost) {
                        Log.d(TAG, "  TPass引导票，进入引导流程...")
                        return if (completeGuideFlow(location)) GUIDE_OK else null
                    }
                    return ticket
                }

                Log.d(TAG, "  ticket不在query中，尝试跟随重定向链...")
                val nextUrl = if (location.startsWith("http")) location
                else "https://pass.neu.edu.cn$location"
                return followRedirectChain(nextUrl)
            }
        } else if (response.code == 200) {
            val html = response.body?.string() ?: ""
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("title")?.text() ?: "无标题"
            Log.w(TAG, "  POST返回200，页面标题: $title, HTML长度=${html.length}")
            val errorMsg = doc.selectFirst(".auth_error, .error, .alert, #msg, .login-error, [class*=error]")
            if (errorMsg != null) {
                Log.w(TAG, "  错误信息: ${errorMsg.text()}")
            }
            Log.d(TAG, "  200页面HTML前500字: ${html.take(500)}")
        } else {
            Log.w(TAG, "  非预期的响应码: ${response.code}")
        }

        return null
    }

    private fun followRedirectChain(startUrl: String): String? {
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        var currentUrl = startUrl
        for (i in 0 until 10) {
            Log.d(TAG, "  跟随重定向[$i]: $currentUrl")
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            val response = noRedirectClient.newCall(request).execute()

            if (response.code in 300..399) {
                val location = response.header("Location") ?: return null
                val uri = android.net.Uri.parse(location)
                val ticket = uri.getQueryParameter("ticket")
                if (ticket != null) {
                    val isPassHost = uri.host?.contains("pass.neu.edu.cn") == true
                    if (isPassHost) {
                        Log.d(TAG, "    TPass引导票，跟随完成引导流程...")
                        return if (completeGuideFlow(location)) GUIDE_OK else null
                    }
                    return ticket
                }
                currentUrl = if (location.startsWith("http")) location
                else "https://pass.neu.edu.cn$location"
            } else {
                Log.d(TAG, "    非重定向，停止跟随")
                return null
            }
        }
        Log.w(TAG, "  达到最大重定向次数，未找到ticket")
        return null
    }

    private fun completeGuideFlow(guideUrl: String): Boolean {
        Log.d(TAG, "  进入引导流程...")

        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        var currentUrl = guideUrl
        loop@ for (i in 0 until 10) {
            Log.d(TAG, "  引导[$i]: $currentUrl")
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            val response = noRedirectClient.newCall(request).execute()
            Log.d(TAG, "    → ${response.code}")

            when {
                response.code in 300..399 -> {
                    // 重定向 → 跟随
                    val location = response.header("Location") ?: return false
                    Log.d(TAG, "    Location: ${location.take(150)}")
                    val locUri = android.net.Uri.parse(location)
                    // 如果重定向到了 ecode（检查 host 而非 url 字符串），跟随全部重定向链
                    val isEcode = locUri.host?.contains("ecode.neu.edu.cn") == true
                    if (isEcode) {
                        Log.d(TAG, "    重定向到ecode，捕获XSRF-TOKEN...")
                        val captureReq = Request.Builder()
                            .url(location)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                            .get()
                            .build()
                        val captureResp = noRedirectClient.newCall(captureReq).execute()
                        captureResp.headers("Set-Cookie").forEach { c ->
                            if (c.trim().startsWith("XSRF-TOKEN=", ignoreCase = true)) {
                                val token = c.trim().removePrefix("XSRF-TOKEN=").removePrefix("xsrf-token=").split(";").first()
                                credentialManager.saveXSRFToken(token)
                            }
                        }
                        return credentialManager.getXSRFToken() != null
                    }
                    // 还在 pass.neu.edu.cn → 继续跟随
                    currentUrl = if (location.startsWith("http")) location
                    else "https://pass.neu.edu.cn$location"
                }
                response.code == 200 -> {
                    // HTML 页面 → 解析并尝试继续
                    val html = response.body?.string() ?: return false
                    val doc = Jsoup.parse(html)
                    Log.d(TAG, "    页面标题: ${doc.title()}")

                    // 检查 meta 刷新
                    val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
                    if (metaRefresh != null) {
                        val content = metaRefresh.attr("content")
                        Log.d(TAG, "    meta刷新: $content")
                        val urlMatch = Regex("url=(.*)", RegexOption.IGNORE_CASE).find(content)
                        val redirectUrl = urlMatch?.groupValues?.get(1)
                        if (redirectUrl != null) {
                            currentUrl = if (redirectUrl.startsWith("http")) redirectUrl
                            else "https://pass.neu.edu.cn$redirectUrl"
                            continue
                        }
                    }

                    // 检查引导页表单
                    val form = doc.selectFirst("form")
                    if (form != null) {
                        val formAction = form.attr("action")
                        val formMethod = form.attr("method").ifEmpty { "get" }
                        Log.d(TAG, "    表单: action=$formAction, method=$formMethod")

                        val params = mutableListOf<Pair<String, String>>()
                        form.select("input").forEach { input ->
                            val name = input.attr("name")
                            val value = input.attr("value")
                            if (name.isNotEmpty()) {
                                params.add(name to value)
                                Log.d(TAG, "      input: $name=$value")
                            }
                        }

                        val actionUrl = if (formAction.startsWith("http")) formAction
                        else "https://pass.neu.edu.cn$formAction"

                        if (formMethod.equals("post", ignoreCase = true)) {
                            val formBody = FormBody.Builder().apply {
                                params.forEach { (k, v) -> add(k, v) }
                            }.build()
                            val postReq = Request.Builder()
                                .url(actionUrl)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                                .post(formBody)
                                .build()
                            val postResp = noRedirectClient.newCall(postReq).execute()
                            Log.d(TAG, "    表单提交 → ${postResp.code}")
                            if (postResp.code in 300..399) {
                                val loc = postResp.header("Location") ?: return false
                                currentUrl = if (loc.startsWith("http")) loc
                                else "https://pass.neu.edu.cn$loc"
                            } else {
                                return false
                            }
                        } else {
                            val query = params.joinToString("&") { "${it.first}=${it.second}" }
                            currentUrl = "$actionUrl?$query"
                        }
                    } else {
                        // 检查继续链接
                        val continueLink = doc.selectFirst("a[href*=/tpass/]")
                        if (continueLink != null) {
                            val href = continueLink.attr("href")
                            Log.d(TAG, "    继续链接: $href")
                            currentUrl = if (href.startsWith("http")) href
                            else "https://pass.neu.edu.cn$href"
                        } else {
                            // 尝试获取 Util.load 动态加载的内容
                            // Util.load(url) 用 jQuery.load() 发送相对 URL 请求
                            // 当前页面 /tp_tpass/h5 (非目录)，相对 URL 从父路径 /tp_tpass/ 解析
                            val actParam = android.net.Uri.parse(currentUrl).getQueryParameter("act")
                            if (actParam != null) {
                                Log.d(TAG, "    尝试加载动态内容: act=$actParam")
                                // 尝试多个 URL 模式（浏览器相对解析 + 同目录）
                                val candidateUrls = listOf(
                                    "https://pass.neu.edu.cn/tp_tpass/$actParam?item_id=",
                                    "https://pass.neu.edu.cn/tp_tpass/h5/$actParam?item_id=",
                                    "https://pass.neu.edu.cn/tp_tpass/$actParam",
                                    "https://pass.neu.edu.cn/tp_tpass/h5/$actParam",
                                )
                                for (url in candidateUrls) {
                                    val loadedResult = tryLoadDynamicContent(url, noRedirectClient)
                                    if (loadedResult != null) {
                                        currentUrl = loadedResult
                                        continue@loop  // 跳到外层循环继续
                                    }
                                }
                            }

                            // 尝试用TGT直接换取service ticket
                            val tgtMatch = Regex("tgt\\s*=\\s*\"(TGT-[^\"]+)\"").find(html)
                            if (tgtMatch != null) {
                                val tgt = tgtMatch.groupValues[1]
                                Log.d(TAG, "    尝试TGT换票")
                                val st = exchangeTgtForSt(tgt)
                                if (st != null) {
                                    Log.d(TAG, "    TGT换票成功, 兑换ticket...")
                                    if (redeemTicket(st)) return true
                                }
                            }

                            Log.w(TAG, "    未找到继续方式，引导流程失败")
                            doc.select("script").forEachIndexed { i, s ->
                                val src = s.attr("src")
                                val text = s.html().take(500)
                                if (src.isNotEmpty()) Log.d(TAG, "    script[src]=$src")
                                if (text.isNotEmpty()) Log.d(TAG, "    script[$i]=$text")
                            }
                            Log.d(TAG, "    HTML: ${html.take(3000)}")
                            return false
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "    非预期响应: ${response.code}")
                    return false
                }
            }
        }
        Log.w(TAG, "  引导流程达到最大步数")
        return false
    }

    private fun tryLoadDynamicContent(loadedUrl: String, client: OkHttpClient): String? {
        Log.d(TAG, "    GET动态内容: $loadedUrl")
        val req = Request.Builder()
            .url(loadedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Accept", "text/html, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://pass.neu.edu.cn/tp_tpass/h5")
            .get()
            .build()
        val resp = client.newCall(req).execute()
        Log.d(TAG, "    动态内容响应: ${resp.code}")
        if (resp.code in 300..399) {
            val loc = resp.header("Location") ?: return null
            Log.d(TAG, "    重定向: ${loc.take(150)}")
            return if (loc.startsWith("http")) loc else "https://pass.neu.edu.cn$loc"
        }
        if (resp.code != 200) return null
        val html = resp.body?.string() ?: return null
        val doc = Jsoup.parse(html)
        Log.d(TAG, "    动态内容标题: ${doc.title()}, HTML长度: ${html.length}")

        // 解析 <script type="text/html"> 模板中的表单（客户端模板渲染）
        var form = doc.selectFirst("form")
        if (form == null) {
            val templates = doc.select("script[type=text/html]")
            Log.d(TAG, "    检查${templates.size}个模板...")
            for (tmpl in templates) {
                val tmplHtml = tmpl.html()
                Log.d(TAG, "    模板[${tmpl.id()}]: ${tmplHtml.take(2000)}")
                val tmplDoc = Jsoup.parse(tmplHtml)
                form = tmplDoc.selectFirst("form")
                if (form != null) {
                    Log.d(TAG, "    在模板中找到表单!")
                    break
                }
            }
        }
        if (form != null) {
            val action = form.attr("action")
            val method = form.attr("method").ifEmpty { "get" }
            Log.d(TAG, "    动态表单: action=$action, method=$method")
            val params = mutableListOf<Pair<String, String>>()
            form.select("input").forEach { input ->
                val n = input.attr("name")
                val v = input.attr("value")
                if (n.isNotEmpty()) params.add(n to v)
            }
            form.select("button, input[type=submit]").forEach { btn ->
                val n = btn.attr("name")
                val v = btn.attr("value")
                if (n.isNotEmpty()) params.add(n to v)
            }
            val actionUrl = if (action.startsWith("http")) action
            else "https://pass.neu.edu.cn$action"
            if (method.equals("post", ignoreCase = true)) {
                val body = FormBody.Builder().apply {
                    params.forEach { (k, v) -> add(k, v) }
                }.build()
                val postReq = Request.Builder()
                    .url(actionUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", loadedUrl)
                    .post(body)
                    .build()
                val postResp = client.newCall(postReq).execute()
                Log.d(TAG, "    动态表单提交 → ${postResp.code}")
                if (postResp.code in 300..399) {
                    val loc = postResp.header("Location") ?: return null
                    return if (loc.startsWith("http")) loc else "https://pass.neu.edu.cn$loc"
                }
                if (postResp.code == 200) {
                    val resultJson = postResp.body?.string()
                    Log.d(TAG, "    表单提交响应: ${resultJson?.take(500)}")
                    // 可能是JSON响应，检查是否有redirect URL
                    if (resultJson != null) {
                        try {
                            val jo = org.json.JSONObject(resultJson)
                            val redirect = jo.optString("redirect", jo.optString("url", ""))
                            if (redirect.isNotEmpty()) {
                                return if (redirect.startsWith("http")) redirect
                                else "https://pass.neu.edu.cn$redirect"
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                val query = params.joinToString("&") { "${it.first}=${it.second}" }
                return "$actionUrl?$query"
            }
        }
        Log.d(TAG, "    动态内容中无表单, HTML: ${html.take(5000)}")
        return null
    }

    private fun exchangeTgtForSt(tgt: String): String? {
        val serviceUrl = "https://ecode.neu.edu.cn/ecode/api/sso/login"
        val apiPaths = listOf(
            "https://pass.neu.edu.cn/tpass/v1/tickets",
            "https://pass.neu.edu.cn/tp_tpass/v1/tickets",
        )
        for (basePath in apiPaths) {
            try {
                val url = "$basePath/$tgt"
                Log.d(TAG, "    CAS REST: POST ticket endpoint")
                val body = FormBody.Builder()
                    .add("service", serviceUrl)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .post(body)
                    .build()
                val resp = client.newBuilder()
                    .followRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute()
                Log.d(TAG, "    CAS REST响应: ${resp.code}")
                if (resp.code == 201) {
                    val st = resp.body?.string()?.trim()
                    if (st != null && st.startsWith("ST-")) {
                        Log.d(TAG, "    获得ST")
                        return st
                    }
                }
                if (resp.code == 200) {
                    val st = resp.body?.string()?.trim()
                    if (st != null && st.startsWith("ST-")) {
                        Log.d(TAG, "    获得ST(200)")
                        return st
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "    CAS REST失败: ${e.message}")
            }
        }
        return null
    }

    private fun redeemTicket(ticket: String): Boolean {
        val url = "https://ecode.neu.edu.cn/ecode/api/sso/login?ticket=$ticket"
        Log.d(TAG, "  redeemTicket")

        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .get()
            .build()

        val response = noRedirectClient.newCall(request).execute()
        Log.d(TAG, "  响应码: ${response.code}")

        var found = false
        response.headers("Set-Cookie").forEach { cookieStr ->
            if (cookieStr.trim().startsWith("XSRF-TOKEN=", ignoreCase = true)) {
                val token = cookieStr.trim()
                    .removePrefix("XSRF-TOKEN=")
                    .removePrefix("xsrf-token=")
                    .split(";")
                    .first()
                credentialManager.saveXSRFToken(token)
                found = true
            }
        }

        if (response.code in 300..399) {
            val location = response.header("Location")
            if (location != null) {
                val followUrl = if (location.startsWith("http")) location
                else "https://ecode.neu.edu.cn$location"
                casClient.newCall(
                    Request.Builder().url(followUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                        .get().build()
                ).execute()
            }
        }

        if (!found) Log.w(TAG, "  未找到XSRF-TOKEN")
        return found
    }
}
