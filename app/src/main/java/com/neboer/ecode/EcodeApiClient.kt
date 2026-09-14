package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 二维码拉取结果:区分会话失效与网络异常,UI 据此决定停在空态等登录还是退避重试 */
sealed class QrFetchResult {
    /** qrCode + 服务端给的有效期(data[0].attributes.qrInvalidTime,毫秒;字段缺失为 null) */
    data class Success(val qrCode: String, val invalidAtMs: Long?) : QrFetchResult()

    /** CASTGC 失效且静默续期失败,需重新走 WebView 登录 */
    object AuthExpired : QrFetchResult()

    /** 网络不可达/超时;不清会话,可退避重试 */
    object NetworkError : QrFetchResult()
}

/**
 * ecode API 客户端。会话来源 = WebViewCookieJar(CookieManager 单一存储):
 * - 鉴权靠 HttpOnly 的 SESSION cookie(按域由 CookieJar 自动带上),见下方实测说明
 * - XSRF-TOKEN 每次请求时从 CookieManager 现读,不用缓存值(服务端是"缺了就发、带了就不发",
 *   不是逐响应轮换:客户端没带时它才在响应里补发一个新的,jar 自动回写)
 * - 首次请求失败时用 CASTGC 静默续期后重试一次;续期失败返回 AuthExpired,由 UI 引导重新登录
 * - IOException 一律归为 NetworkError(此前会穿透到协程导致闪退)
 *
 * 鉴权模型(2026-09-10 控制变量实测,详见 AGENTS.md):
 * - qr-code 只认 SESSION——ecode 的 sso/login 种下的会话槽;伪造值或只带 XSRF-TOKEN 都返回 401
 * - X-XSRF-TOKEN 头在这个 GET 端点上不被校验(缺失/错值/错配一律 200)。CSRF 通常只作用于写方法,
 *   所以继续照发以贴近浏览器指纹,但别把它当鉴权头
 * - SESSION 寿命约 100 天且不可续:扫过的 ecode 端点没有任何一个重发它
 */
class EcodeApiClient(private val client: OkHttpClient) {

    private val apiClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cas = CasAuthClient(client)

    companion object {
        private const val TAG = "EcodeApi"
        private const val ECODE_HOST = "ecode.neu.edu.cn"
    }

    fun fetchQRCode(): QrFetchResult {
        return try {
            tryFetch()?.let {
                Log.d(TAG, "fetchQRCode: 首次请求成功")
                return it
            }

            Log.w(TAG, "fetchQRCode: 首次请求失败(401/token过期),CASTGC 静默续期后重试")
            if (!renewSession()) {
                Log.e(TAG, "fetchQRCode: 静默续期失败(CASTGC失效),需重新登录")
                return QrFetchResult.AuthExpired
            }

            Log.d(TAG, "fetchQRCode: 续期成功,重试fetch")
            tryFetch() ?: QrFetchResult.AuthExpired
        } catch (e: IOException) {
            Log.w(TAG, "fetchQRCode: 网络异常", e)
            QrFetchResult.NetworkError
        }
    }

    /**
     * 只做一次真校验:qr-code 拿到 200 才算这个会话真的可用。不续期、不改任何状态。
     *
     * 用它当"登录是否真的成功"的判据,而不是看 cookie 在不在 —— SESSION 是约 100 天的持久
     * cookie,它还在完全不代表服务端还认它(2026-09 的无限闪屏就是拿它当成功的后果)。
     */
    fun hasAuthenticatedSession(): Boolean = tryFetch() != null

    /**
     * 靠共享 cookie 罐里活着的 CASTGC 换票,并跟随到 ecode 把票消费掉 —— 消费那一跳
     * 才会把 SESSION 会话槽标记为已认证(票本身不签发新 cookie)。
     *
     * 只看"是否落到 ecode 主机",**不掺 `hasEcodeSession()`**:那是在用"cookie 存在"当凭据
     * (SESSION 是约 100 天的持久 cookie,在不在与服务端认不认它是两件事,见 AGENTS.md)。
     * 换票成功与否由调用方紧接着的那次重试拉码来判定,那才是真校验。
     */
    private fun renewSession(): Boolean =
        cas.establishSession(CasAuthClient.ECODE_ENTRY, ECODE_HOST) == CasAuthClient.Establish.OK

    /** 成功返回 Success;HTTP 非 200/解析失败返回 null(交给续期流程兜底);IOException 向上抛 */
    private fun tryFetch(): QrFetchResult.Success? {
        val xsrfToken = readXSRFToken()
        Log.d(TAG, "tryFetch: hasToken=${xsrfToken != null}")

        val request = Request.Builder()
            .url("https://ecode.neu.edu.cn/ecode/api/qr-code")
            .header("User-Agent", UserAgent.current)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://ecode.neu.edu.cn/ecode/")
            .header("X-XSRF-TOKEN", xsrfToken ?: "")
            .header("Origin", "https://ecode.neu.edu.cn")
            .get()
            .build()

        val response = apiClient.newCall(request).execute()
        Log.d(TAG, "tryFetch: HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.w(TAG, "tryFetch失败: HTTP ${response.code}(会话失效或 token 缺失)")
            return null
        }

        val body = response.body?.string()
        if (body == null) {
            Log.w(TAG, "tryFetch: response.body为空")
            return null
        }

        try {
            val attrs = JSONObject(body)
                .getJSONArray("data")
                .getJSONObject(0)
                .getJSONObject("attributes")
            val qrCode = attrs.getString("qrCode")
            val invalidAtMs = attrs.optLong("qrInvalidTime", 0L)
                .takeIf { it > 0 }
                ?.let { toMs(it) }
            Log.i(TAG, "二维码获取成功, invalidAtMs=$invalidAtMs")
            return QrFetchResult.Success(qrCode, invalidAtMs)
        } catch (e: Exception) {
            Log.e(TAG, "JSON解析失败", e)
            return null
        }
    }

    /** qrInvalidTime 是 unix 秒级时间戳,容错处理已是毫秒的情况(与 webview-demo 的 toMs 一致) */
    private fun toMs(ts: Long): Long = if (ts > 10_000_000_000L) ts else ts * 1000

    /** XSRF-TOKEN 每次从 CookieManager 现读,不用缓存值 */
    private fun readXSRFToken(): String? {
        val raw = android.webkit.CookieManager.getInstance()
            .getCookie("https://ecode.neu.edu.cn/") ?: return null
        return raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("XSRF-TOKEN=", ignoreCase = true) }
            ?.substringAfter('=')
    }
}
