package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ecode API 客户端。会话来源 = WebViewCookieJar(CookieManager 单一存储):
 * - XSRF-TOKEN 每次请求时从 CookieManager 读取(ecode 每个响应会轮换该 cookie,jar 自动回写)
 * - 401 时用 CASTGC 静默续期后重试一次;续期失败(CASTGC 失效)返回 null,由 UI 引导重新登录
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

    fun fetchQRCode(): String? {
        Log.d(TAG, "fetchQRCode开始: hasToken=${WebViewCookieJar.hasEcodeSession()}")
        tryFetch()?.let {
            Log.d(TAG, "fetchQRCode: 首次请求成功")
            return it
        }

        Log.w(TAG, "fetchQRCode: 首次请求失败(401/token过期),CASTGC 静默续期后重试")
        if (!renewer.renewEcodeSession()) {
            Log.e(TAG, "fetchQRCode: 静默续期失败(CASTGC失效),需重新登录")
            return null
        }

        Log.d(TAG, "fetchQRCode: 续期成功,重试fetch")
        return tryFetch()
    }

    private fun tryFetch(): String? {
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
            val qrCode = JSONObject(body)
                .getJSONArray("data")
                .getJSONObject(0)
                .getJSONObject("attributes")
                .getString("qrCode")
            Log.i(TAG, "二维码获取成功")
            return qrCode
        } catch (e: Exception) {
            Log.e(TAG, "JSON解析失败", e)
            return null
        }
    }

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
