package com.neboer.ecode

import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** 登录页里这次要用的东西。lt 与 execution 都是一次性的,每次加载现取,绝不缓存 */
data class CasLoginForm(
    val action: String,
    val lt: String,
    val execution: String,
    val publicKeyB64: String,
)

/**
 * 二验页的表单。[hidden] 是 `#second_auth_form` 里的全部隐藏字段,**原样重放**——
 * 我们只覆盖 imgCode/scendAuthCode/method/_eventId 四个,于是永远不需要预先知道字段表,
 * 校方加字段也不会把我们打挂(见 AGENTS.md 的二验契约)。
 *
 * 可序列化是为了能随 Intent 从"静默续期"那一侧交给登录页继续用:账密刚被服务端接受,
 * 表单里的一次性 token 还在,没必要让登录页把同样的账密再提交一遍。
 */
data class CasSecondFactorForm(
    val action: String,
    val hidden: Map<String, String>,
    /** mobile / email / wechatQrCode,由服务端渲染了哪个 #secondAuthBy* 按钮决定 */
    val mode: String,
) : java.io.Serializable

/** 加载登录页的三种去向 */
sealed class CasFormLoad {
    data class Ok(val form: CasLoginForm) : CasFormLoad()

    /** 一上来就 302:本地有活着的 TGT,不必输账密,跟着这条 Location 走即可 */
    data class Redirected(val location: String) : CasFormLoad()

    data class Failed(val detail: String) : CasFormLoad()
}

/** 一次提交(账密或二验)的判定 */
sealed class CasStep {
    /** 302 且 Location 带 ticket=ST-:通过,票在 Location 里等着被服务端消费 */
    data class Ticket(val location: String) : CasStep()

    /** 200 且是二验页:账密通过了,但必须过验证码 */
    data class SecondFactor(val form: CasSecondFactorForm) : CasStep()

    /** 200 且回到登录页并带错误文案;null = 被弹回表单但服务端没给文案 */
    data class BadCredentials(val serverMessage: String?) : CasStep()

    /** 既不是票也不是能识别的页面:结构变了或响应异常 */
    data class Unclear(val detail: String) : CasStep()
}

/** 发短信那一步的结局 */
sealed class CasSmsResult {
    object Sent : CasSmsResult()
    data class Rejected(val message: String) : CasSmsResult()
    data class Failed(val detail: String) : CasSmsResult()
}

/**
 * 原生 CAS 客户端:不再用 WebView 驱动登录页,自己把表单走完。
 *
 * 为什么可行(实测,不是推断):
 * - 提交的字段与页面自己的 `login()` 完全一致 —— `rsa`(jsencrypt 的 RSA(账号+密码))、
 *   `ul`/`pl`(明文长度)、`lt`、`execution`、`_eventId=submit`,外加三个恒为空的
 *   `t_un`/`t_pd`/`t_c`;字段顺序也照抓包。`demo/fingerprint-lab` 用这套字段在真机上
 *   跑出 10/10 被接受并种下 CASTGC
 * - RSA 公钥优先从 `login_neu.js` 现场解析(它硬编码在里面),解析不到才用兜底常量
 * - 二验那三步契约来自 `.har/webvpn.neu.edu.cn.har` 的完整往返 + `.har/_js2/login_second.js`
 *   的客户端脚本(见 AGENTS.md)
 *
 * 分工:本类只负责协议,**不含任何界面**。静默重登就是直接调 [loginSilently],
 * 交互登录将来由原生表单调 [fetchLoginForm]/[submitCredentials]/[submitSecondFactor]。
 * 这样"登录"与"静默续期"走的是同一段代码,只是前者有人在旁边填验证码。
 *
 * IOException 一律向上抛(与仓库其他客户端一致),由调用方归类成网络问题;
 * 判定失败用返回值表达,不用异常。
 */
class CasAuthClient(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "CasAuthClient"

        private const val CAS_HOST = "pass.neu.edu.cn"
        private const val CAS_ORIGIN = "https://$CAS_HOST"
        private const val LOGIN_PATH = "/tpass/login"
        private const val CAPTCHA_PATH = "/tpass/code"
        private const val SMS_PATH = "/tpass/secondAuthCode"
        private const val LOGIN_JS_PATH = "/tpass/comm/neu/js/login_neu.js"

        /** 本应用唯一需要票的服务:ecode 的 SSO 入口(二维码会话由它建立) */
        const val ECODE_SERVICE = "https://ecode.neu.edu.cn/ecode/api/sso/login"

        /** 拿去 CAS 换票的入口 URL(与抓包逐字节一致,别"顺手"重排参数) */
        const val ECODE_ENTRY =
            "$CAS_ORIGIN$LOGIN_PATH?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"

        /** 兜底公钥:与 login_neu.js 里硬编码的那把一致(2026-09 抓包核对) */
        private const val FALLBACK_PUBKEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnjA28DLKXZzxb" +
                "Kmo9/1WkVLf1mr+wtLXLXt6sC4WiBCtsbzF5ewm7ARZeAdS3iZtqlYPn6" +
                "IcUoOw42H8nAK/tfFcIb6dZ1K0atn0U39oWCGPzYuKtLJeMuNZiDXVuA" +
                "XtojrckOjLW9B3gUnaNGLuIx0fYe66l0o9WjU2cGLNZQfiIxs2h00z1EA" +
                "9IdSnVxiVQWSD+lsP3JZXh2TT287la4Y4603SQNKTK/QvXfcmccwTEd1I" +
                "W6HwGxD6QrkInBiHisKWxmveN7UDSaQRZ/J97G0YC32pD38WT53izXeK0" +
                "p/kU/X37VP555um1wVWFvPIuc9I7gMP1+hq5a+X6c++tQIDAQAB"

        /** 二验页的表单 id(与二验页的脚本一致,已核对) */
        private const val SECOND_FACTOR_FORM_ID = "second_auth_form"

        /** 这四个字段由我们覆盖,其余隐藏字段原样重放 */
        private val OVERRIDDEN_FIELDS = setOf("imgCode", "scendAuthCode", "method", "_eventId")

        /** RSA-2048 + PKCS#1 v1.5 的明文上限:256 - 11 = 245 字节 */
        private const val MAX_PLAINTEXT_BYTES = 245
    }

    /** 请求类型:导航(document)还是脚本发起的 XHR。两者的 Sec-Fetch-* 不一样 */
    private enum class Kind { DOCUMENT, XHR }

    /** 兑票落地结果:落到期望主机才算拿到;落在 CAS 自己身上说明没有有效 TGT */
    enum class Establish { OK, NO_TGT, ELSEWHERE }

    /**
     * 浏览器导航必带的头。少了它们,一个自称 Chrome 的客户端在风控眼里就很可疑 ——
     * 而校方风控能不能看出差别,直接决定这条原生路线可不可用。
     *
     * 只做到"连贯"为止,不追求逐字节复刻:Chrome 的头顺序与 `sec-ch-ua` 系列强依赖它自己的
     * 网络栈,用 OkHttp 复刻注定不完美。实测(demo/fingerprint-lab 10/10)证明这套字段 +
     * 头是够用的;真出问题时的症状是登录被拒(会记进熔断),所以宁可现在就把这些补齐。
     */
    private fun navHeaders(builder: Request.Builder, kind: Kind, referer: String? = null) {
        builder.header("User-Agent", UserAgent.current)
        builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        builder.header("Upgrade-Insecure-Requests", "1")
        if (kind == Kind.DOCUMENT) {
            builder.header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            builder.header("Sec-Fetch-Dest", "document")
            builder.header("Sec-Fetch-Mode", "navigate")
            builder.header("Sec-Fetch-Site", if (referer == null) "none" else "same-origin")
            builder.header("Sec-Fetch-User", "?1")
        } else {
            builder.header("Accept", "application/json, text/plain, */*")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "same-origin")
            builder.header("X-Requested-With", "XMLHttpRequest")
        }
        referer?.let { builder.header("Referer", it) }
    }

    private val noRedirect = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val follow = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── 静默重登:一条不用界面的完整路径 ─────────────────────────────────────

    /**
     * 只用账密换一次 TGT,全程无界面。
     *
     * **撞到二次验证立刻停**——短信只有 [requestSmsCode] 才会真的发出去,本方法永远不调用它,
     * 所以它不可能替用户发短信,也不可能替用户做图形验证码。这正是静默路径的安全边界。
     *
     * [verify] 是"票换到了"之后必须过的一关:**成功不等于"cookie 里有东西"**,
     * 而是"服务端确实认这个会话"。上一版无限闪屏的根因就是拿本地 cookie 存在当成功,
     * 而 SESSION 是 100 天的持久 cookie,它还在不代表会话还活着。
     */
    fun loginSilently(username: String, password: String, verify: () -> Boolean): SilentLoginOutcome {
        return when (val load = fetchLoginForm()) {
            is CasFormLoad.Failed -> SilentLoginOutcome.Error(load.detail)

            // 本地 TGT 还活着:CAS 直接给票,不必输账密(也说明这一次其实只是会话槽失效)
            is CasFormLoad.Redirected -> {
                Log.i(TAG, "本地 TGT 仍可用,直接兑票")
                followTicket(load.location)
                if (verify()) SilentLoginOutcome.Success
                else SilentLoginOutcome.Error("原有 TGT 兑票后会话仍不可用")
            }

            is CasFormLoad.Ok -> when (val step = submitCredentials(load.form, username, password)) {
                is CasStep.Ticket -> {
                    Log.i(TAG, "账密通过,已拿到 ticket,跟随消费")
                    followTicket(step.location)
                    if (verify()) SilentLoginOutcome.Success
                    else SilentLoginOutcome.Error("换到票但会话校验失败")
                }

                is CasStep.SecondFactor -> {
                    Log.i(TAG, "账密通过,但服务端要求二次验证;静默到此为止(不发短信),把表单交回界面")
                    SilentLoginOutcome.NeedSecondFactor(step.form)
                }

                is CasStep.BadCredentials -> {
                    Log.w(TAG, "账密被拒(服务端文案: ${step.serverMessage ?: "未给出"})")
                    SilentLoginOutcome.Rejected
                }

                is CasStep.Unclear -> SilentLoginOutcome.Error(step.detail)
            }
        }
    }

    // ── 换票:交互登录与静默登录共用的最后一步 ───────────────────────────────

    /**
     * 交互登录的最后一步:把票交给服务端消费,再过一遍真校验。
     *
     * 与 [loginSilently] 内部走的是同一段逻辑([followTicket] + [verify]),所以"交互登录成功"
     * 与"静默续期成功"的定义完全相同 —— 都是"票被消费掉且服务端认这个会话",而不是
     * "本地 cookie 里有东西"。
     */
    fun consumeTicket(location: String, verify: () -> Boolean): Boolean {
        followTicket(location)
        return verify()
    }

    /**
     * 拿共享 cookie 罐里活着的 TGT 去换一张票,并跟随到服务端把票消费掉。
     *
     * 服务端消费票的那一跳才会下发真正有用的凭据(ecode 侧是把 SESSION 会话槽标记为已认证,
     * 门户侧是下发 CK_LC/CK_VL),所以必须跟随重定向,不能只看 302。
     *
     * [expectedHost] 是"票被消费掉"的证据:没有有效 TGT 时 CAS 会把 tpass 登录页
     * (200 HTML)直接返回,不再 302 出票。
     */
    fun establishSession(entryUrl: String, expectedHost: String): Establish {
        val response = follow.newCall(
            Request.Builder().url(entryUrl).header("User-Agent", UserAgent.current).get().build()
        ).execute()
        val landed = response.request.url
        response.close()
        Log.d(TAG, "兑票落地 $landed (期望主机 $expectedHost)")
        return when (landed.host) {
            expectedHost -> Establish.OK
            CAS_HOST -> Establish.NO_TGT
            else -> Establish.ELSEWHERE
        }
    }

    /** 跟随 302 的 Location 把票交给服务端消费(失败只记日志:后续的 verify 会给出结论) */
    private fun followTicket(location: String) {
        try {
            follow.newCall(
                Request.Builder().url(location).header("User-Agent", UserAgent.current).get().build()
            ).execute().use { resp ->
                Log.d(TAG, "兑票链落地: ${resp.request.url}(HTTP ${resp.code})")
            }
        } catch (e: IOException) {
            Log.w(TAG, "兑票链跟随失败", e)
        }
    }

    // ── 账密登录:分三步,便于 UI 在中间插验证码 ──────────────────────────────

    /**
     * 加载登录页并取出 lt/execution/表单地址/公钥。
     *
     * 二验的判定不需要渲染:登录页里 `second_auth_form`/`scendAuthCode`/`imgCode` 出现次数
     * 为 0(已按字节核对),它们只在二验页出现;反过来二验页也会有 `#loginForm`,
     * 所以 [classify] 先判二验再判登录页。
     */
    fun fetchLoginForm(): CasFormLoad {
        val (code, location, html) = get(ECODE_ENTRY, noRedirect)
        if (code in 300..399) {
            return if (location == null) CasFormLoad.Failed("$code 但没有 Location")
            else CasFormLoad.Redirected(location)
        }
        if (code != 200) return CasFormLoad.Failed("登录页返回 HTTP $code")

        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form#loginForm")
            ?: return CasFormLoad.Failed("登录页里没找到 form#loginForm(页面结构可能变了)")
        val lt = form.selectFirst("input#lt")?.attr("value").orEmpty()
        val execution = form.selectFirst("input[name=execution]")?.attr("value").orEmpty()
        if (lt.isEmpty() || execution.isEmpty()) {
            return CasFormLoad.Failed("lt/execution 解析不全(lt=${lt.take(12)}…, execution='$execution')")
        }
        return CasFormLoad.Ok(
            CasLoginForm(
                action = resolveAction(form.attr("action")),
                lt = lt,
                execution = execution,
                publicKeyB64 = fetchPublicKey(),
            )
        )
    }

    /**
     * 提交账密。不跟随重定向:302 的 Location 带不带 ticket 是最干净的判据。
     *
     * 密码只出现在 [rsaEncrypt] 的入参里,任何时候都不进日志;`pl` 传的是**长度**不是密码。
     * 页面自己也一样:它提交前把 `#un`/`#pd` 设成 disabled(disabled 控件不进表单),
     * 所以明文账密根本不出现在 POST 里(2026-09-14 抓包核实)。
     *
     * 最后三个 `t_un`/`t_pd`/`t_c` 在页面的 DOM 里存在但恒为空值;2026-09-14 的抓包显示
     * 浏览器那一次实际没有提交它们。空字段对服务端无害(真机 10/10 通过),留着是为了
     * 与当初验证过的那份字段表保持一致,不为了"少发三个空串"去动已验过的请求。
     */
    fun submitCredentials(form: CasLoginForm, username: String, password: String): CasStep {
        val plain = username + password
        if (plain.toByteArray(StandardCharsets.UTF_8).size > MAX_PLAINTEXT_BYTES) {
            return CasStep.Unclear("账号+密码超过 $MAX_PLAINTEXT_BYTES 字节,RSA-2048 装不下")
        }
        val body = FormBody.Builder()
            .add("rsa", rsaEncrypt(form.publicKeyB64, plain))
            .add("ul", username.length.toString())
            .add("pl", password.length.toString())
            .add("lt", form.lt)
            .add("execution", form.execution)
            .add("_eventId", "submit")
            .add("t_un", "")
            .add("t_pd", "")
            .add("t_c", "")
            .build()
        val (code, location, html) = post(form.action, body)
        return classify(code, location, html)
    }

    /** 图形验证码图片(服务端给 image/jpeg,原样返回交给 UI 解码)。失败返回 null */
    fun fetchCaptcha(): ByteArray? {
        val builder = Request.Builder().url("$CAS_ORIGIN$CAPTCHA_PATH?${System.currentTimeMillis()}")
        navHeaders(builder, Kind.XHR, ECODE_ENTRY)
        return follow.newCall(builder.get().build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * 触发短信。**这是唯一会让短信真的发出去的接口**,所以只在交互路径上、由用户点击调用;
     * 静默路径永远不碰它。
     *
     * 契约(取自二验页自己的 login_second.js,并经抓包核对):
     * POST 表单 `code=<图形码>&method=<mobile|email>`,响应 JSON `{"success":bool,"message":…}`。
     */
    fun requestSmsCode(form: CasSecondFactorForm, captcha: String): CasSmsResult {
        val body = FormBody.Builder()
            .add("code", captcha)
            .add("method", form.mode)
            .build()
        val builder = Request.Builder().url("$CAS_ORIGIN$SMS_PATH")
        navHeaders(builder, Kind.XHR, ECODE_ENTRY)
        builder.header("Origin", CAS_ORIGIN)
        builder.post(body)
        return noRedirect.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                CasSmsResult.Failed("HTTP ${resp.code}: ${text.take(120)}")
            } else {
                try {
                    val json = JSONObject(text)
                    if (json.optBoolean("success")) CasSmsResult.Sent
                    else CasSmsResult.Rejected(json.optString("message"))
                } catch (e: Exception) {
                    CasSmsResult.Failed("响应不是 JSON: ${text.take(120)}")
                }
            }
        }
    }

    /**
     * 提交二验(图形码 + 短信码)。隐藏字段原样重放,我们只覆盖那四个,
     * 所以 `RelayState`/`execution` 之类的一次性 token 不需要我们理解。
     */
    fun submitSecondFactor(form: CasSecondFactorForm, captcha: String, smsCode: String): CasStep {
        val builder = FormBody.Builder()
        form.hidden.filterKeys { it !in OVERRIDDEN_FIELDS }
            .forEach { (k, v) -> builder.add(k, v) }
        builder.add("imgCode", captcha)
        builder.add("scendAuthCode", smsCode)
        builder.add("method", form.mode)
        builder.add("_eventId", "submit")
        val (code, location, html) = post(form.action, builder.build())
        return classify(code, location, html)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 提交后的判定顺序:先票,再二验页,最后登录页(带不带错误文案) */
    private fun classify(code: Int, location: String?, html: String): CasStep {
        if (code in 300..399) {
            if (location == null) return CasStep.Unclear("$code 但没有 Location")
            return when {
                location.contains("ticket=ST-", ignoreCase = true) -> CasStep.Ticket(location)
                location.contains(LOGIN_PATH) -> CasStep.BadCredentials(null)
                else -> CasStep.Unclear("$code 重定向到非登录页且不带 ticket: ${location.take(160)}")
            }
        }
        if (code != 200) return CasStep.Unclear("未预期的响应码 $code")

        if (html.contains(SECOND_FACTOR_FORM_ID) || html.contains("scendAuthCode")) {
            return CasStep.SecondFactor(parseSecondFactor(html))
        }
        if (html.contains("id=\"loginForm\"")) {
            // 登录页有可见的错误提示才是"账密不对";没有文案的多半是校验码/风控,
            // 但那两种我们都不该计进熔断,所以这里如实返回 null 文案
            return CasStep.BadCredentials(visibleErrorText(html))
        }
        return CasStep.Unclear("200 但既不认识二验页也不认识登录页(${html.length} 字节)")
    }

    private fun parseSecondFactor(html: String): CasSecondFactorForm {
        val doc: Document = Jsoup.parse(html)
        val form = doc.selectFirst("#$SECOND_FACTOR_FORM_ID")
        if (form == null) {
            // 判定已经靠字符串命中了,却找不到表单:只可能是不成对的模板残留
            Log.w(TAG, "响应含 $SECOND_FACTOR_FORM_ID 标记但没有该表单元素")
        }
        val hidden = LinkedHashMap<String, String>()
        form?.select("input[type=hidden]")?.forEach { hidden[it.attr("name")] = it.attr("value") }
        val mode = when {
            doc.selectFirst("#secondAuthByMobile") != null -> "mobile"
            doc.selectFirst("#secondAuthByEmail") != null -> "email"
            doc.selectFirst("#secondAuthByQyQrCode") != null -> "wechatQrCode"
            // 兜底绝不能是 mobile:万一服务端换了按钮 id(如微信扫码改名),我们就会把它当成
            // 短信那条路,给用户一个"看着能用却永远发不出码"的面板。unknown 会落到登录页的
            // "不支持"分支,那里有网页登录逃生口 —— 那是这种情况下唯一真正的出路
            else -> "unknown"
        }
        return CasSecondFactorForm(resolveAction(form?.attr("action").orEmpty()), hidden, mode)
    }

    /**
     * 取出服务端给出的错误文案。
     *
     * 两个元素的先后顺序是有讲究的(2026-09-14 核实):
     * - `#errormsg` 是页面上**可见的那个**,但它在干净页面里就带着一段 `display:none` 的
     *   占位文案「账号不存在！」——直接读它会把占位符当成错误,所以必须看内联 style。
     * - 真实文案是服务端渲染进 `#errormsghide` 的,页面自己的脚本才把它搬进 `#errormsg`
     *   并显示出来(`login_neu.js:152`:`if($("#errormsghide").text()){…}`)。干净页面里
     *   `#errormsghide` 出现 **0 次**,所以"它非空"本身就是强信号;我们不发 JS,只能自己先读它。
     * - 万一哪天服务端改成直接渲染可见的 `#errormsg`,后一条兜住。两条都没有就返回 null,
     *   调用方退回通用文案。
     */
    private fun visibleErrorText(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("#errormsghide")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val el = doc.selectFirst("#errormsg") ?: return null
        if (el.attr("style").contains("none")) return null
        return el.text().trim().takeIf { it.isNotEmpty() }
    }

    /** 公钥优先现场解析(校方换密钥时不会静默失效),失败才回退内置常量 */
    private fun fetchPublicKey(): String {
        return try {
            val (code, _, js) = get("$CAS_ORIGIN$LOGIN_JS_PATH", noRedirect, referer = ECODE_ENTRY)
            if (code == 200) {
                Regex("""publicKeyStr\s*=\s*"([A-Za-z0-9+/=]+)"""").find(js)?.groupValues?.get(1)
                    ?.also {
                        Log.d(TAG, "公钥来自 login_neu.js(与兜底${if (it == FALLBACK_PUBKEY) "一致" else "不同,请留意"})")
                    }
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "取 login_neu.js 失败,用兜底公钥", e)
            null
        } ?: FALLBACK_PUBKEY
    }

    private fun rsaEncrypt(pubKeyB64: String, plain: String): String {
        val key: PublicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(pubKeyB64, Base64.DEFAULT)))
        // 与页面 jsencrypt 的默认填充一致(已用 .har/_fixture/RsaInteropCheck.java 双向验证)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 表单 action 可能是绝对地址、站点绝对路径或相对 /tpass/ 的路径;空则回落到登录入口 */
    private fun resolveAction(action: String): String = when {
        action.startsWith("http") -> action
        action.startsWith("/") -> CAS_ORIGIN + action
        action.isEmpty() -> ECODE_ENTRY
        else -> "$CAS_ORIGIN/tpass/$action"
    }

    private fun get(url: String, http: OkHttpClient, referer: String? = null): Triple<Int, String?, String> {
        val builder = Request.Builder().url(url)
        navHeaders(builder, Kind.DOCUMENT, referer)
        return http.newCall(builder.get().build()).execute().use { resp ->
            Triple(resp.code, resp.header("Location"), resp.body?.string().orEmpty())
        }
    }

    private fun post(url: String, body: FormBody): Triple<Int, String?, String> {
        val builder = Request.Builder().url(url)
        // 表单提交也是导航:Chrome 发它时带的是 document 那套 Sec-Fetch-*
        navHeaders(builder, Kind.DOCUMENT, ECODE_ENTRY)
        builder.header("Origin", CAS_ORIGIN)
        builder.post(body)
        return noRedirect.newCall(builder.build()).execute().use { resp ->
            Triple(resp.code, resp.header("Location"), resp.body?.string().orEmpty())
        }
    }
}
