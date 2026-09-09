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
 * - XSRF-TOKEN 每次请求时从 CookieManager 读取(ecode 每个响应会轮换该 cookie,jar 自动回写)
 * - 首次请求失败时用 CASTGC 静默续期后重试一次;续期失败返回 AuthExpired,由 UI 引导重新登录
 * - IOException 一律归为 NetworkError(此前会穿透到协程导致闪退)
 */
class EcodeApiClient(private val client: OkHttpClient) {

    private val apiClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val renewer = CasSessionRenewer(client)

    companion object {
        private const val TAG = "EcodeApi"
    }

    fun fetchQRCode(): QrFetchResult {
        return try {
            tryFetch()?.let {
                Log.d(TAG, "fetchQRCode: 首次请求成功")
                return it
            }

            Log.w(TAG, "fetchQRCode: 首次请求失败(401/token过期),CASTGC 静默续期后重试")
            if (!renewer.renewEcodeSession()) {
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

    /** 成功返回 Success;HTTP 非 200/解析失败返回 null(交给续期流程兜底);IOException 向上抛 */
    private fun tryFetch(): QrFetchResult.Success? {
        val xsrfToken = readXSRFToken()
        Log.d(TAG, "tryFetch: hasToken=${xsrfToken != null}")

        val request = Request.Builder()
            .url("https://ecode.neu.edu.cn/ecode/api/qr-code")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://ecode.neu.edu.cn/ecode/")
            .header("X-XSRF-TOKEN", xsrfToken ?: "")
            .header("Origin", "https://ecode.neu.edu.cn")
            .get()
            .build()

        val response = apiClient.newCall(request).execute()
        Log.d(TAG, "tryFetch: HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.w(TAG, "tryFetch失败: HTTP ${response.code}(响应轮换的新XSRF-TOKEN已由jar回写)")
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
