package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EcodeApiClient(
    private val client: OkHttpClient,
    private val credentialManager: CredentialManager,
    private val casAuthenticator: CasAuthenticator
) {
    private val apiClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "EcodeApi"
    }

    fun fetchQRCode(): String? {
        Log.d(TAG, "fetchQRCode开始: XSRF-TOKEN=${credentialManager.getXSRFToken()?.take(20) ?: "null"}")
        tryFetch()?.let {
            Log.d(TAG, "fetchQRCode: 首次请求成功")
            return it
        }

        Log.w(TAG, "fetchQRCode: 首次请求失败(401/token过期)，准备用存储凭据重新认证")
        val username = credentialManager.getUsername() ?: run {
            Log.w(TAG, "fetchQRCode: 无存储用户名，无法重新认证")
            return null
        }
        val password = credentialManager.getPassword() ?: run {
            Log.w(TAG, "fetchQRCode: 无存储密码，无法重新认证")
            return null
        }

        Log.d(TAG, "fetchQRCode: 清除旧cookies后调用casAuthenticator.login(user=$username)")
        // 清除所有旧 cookie（CASTGC、XSRF-TOKEN 等），否则 CAS 会因已有会话而跳过登录表单
        (client.cookieJar as? PersistentCookieJar)?.clear()
        if (!casAuthenticator.login(username, password)) {
            Log.e(TAG, "fetchQRCode: 重新认证失败，清空凭据")
            credentialManager.clear()
            return null
        }

        Log.d(TAG, "fetchQRCode: 重新认证成功，重试fetch")
        return tryFetch()
    }

    private fun tryFetch(): String? {
        val xsrfToken = credentialManager.getXSRFToken()
        Log.d(TAG, "tryFetch: XSRF-TOKEN=${xsrfToken?.take(20)}")

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
            Log.w(TAG, "tryFetch失败: HTTP ${response.code}")
            val errBody = response.body?.string()
            Log.d(TAG, "tryFetch: 响应体前500字: ${errBody?.take(500)}")

            response.headers("Set-Cookie").forEach { cookie ->
                Log.d(TAG, "tryFetch: Set-Cookie: ${cookie.take(150)}")
                if (cookie.trim().startsWith("XSRF-TOKEN=", ignoreCase = true)) {
                    val token = cookie.trim()
                        .removePrefix("XSRF-TOKEN=")
                        .removePrefix("xsrf-token=")
                        .split(";")
                        .first()
                    credentialManager.saveXSRFToken(token)
                    Log.d(TAG, "tryFetch: 保存新XSRF-TOKEN: ${token.take(20)}...")
                }
            }
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
            Log.i(TAG, "二维码: ${qrCode.take(30)}...")
            return qrCode
        } catch (e: Exception) {
            Log.e(TAG, "JSON解析失败: body=${body.take(200)}", e)
            return null
        }
    }
}
