package com.neboer.ecode.webviewdemo

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WebView 登录 CAS 测试 demo:
 * 1. 拉起 WebView 加载 CAS 登录页(service 指向 ecode 的 SSO 兑换接口)
 * 2. 用户在 WebView 里正常登录(账密/短信验证/WebVPN 重定向全部交给真实浏览器行为)
 * 3. 轮询 CookieManager:CAS 域出现 CASTGC(TGC)且 ecode 域出现 XSRF-TOKEN 即判定成功
 * 4. 切换到结果页展示拦截到的会话,并用这些 cookie 实测 /ecode/api/qr-code
 */
class WebViewLoginDemoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewLoginDemo"

        /** 校园网直连入口:TPass CAS 登录页,service 指向 ecode 的 SSO 兑换接口 */
        private const val CAS_LOGIN_URL =
            "https://pass.neu.edu.cn/tpass/login?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"
        private const val CAS_ORIGIN = "https://pass.neu.edu.cn"
        private const val ECODE_ORIGIN = "https://ecode.neu.edu.cn"
        private const val WEBVPN_HOST = "https://webvpn.neu.edu.cn"
        private const val QR_API_URL = "https://ecode.neu.edu.cn/ecode/api/qr-code"
        private const val POLL_INTERVAL_MS = 800L
    }

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnVerify: Button
    private lateinit var loginContainer: View
    private lateinit var successContainer: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loginPhase = false

    /** 登录期间轮询 cookie:短信验证等页面里的 AJAX 种下的 cookie 也能捕捉到 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!loginPhase) return
            checkLoginSuccess()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_login_demo)

        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        btnVerify = findViewById(R.id.btnVerify)
        loginContainer = findViewById(R.id.loginContainer)
        successContainer = findViewById(R.id.successContainer)
        etUrl.setText(CAS_LOGIN_URL)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "onPageFinished: $url")
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startLogin() }
        findViewById<Button>(R.id.btnManualDone).setOnClickListener {
            if (!loginPhase) return@setOnClickListener
            // WebVPN 模式下 cookie 可能被网关改名/换域,自动检测不到时给人工兜底
            if (!checkLoginSuccess()) onLoginSuccess("手动标记")
        }
        btnVerify.setOnClickListener { verifySession() }
        findViewById<Button>(R.id.btnRestart).setOnClickListener { restart() }
    }

    private fun startLogin() {
        var url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "URL不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http")) url = "https://$url"

        loginPhase = true
        loginContainer.visibility = View.VISIBLE
        successContainer.visibility = View.GONE
        tvResult.text = ""
        webView.loadUrl(url)
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    /** URL 对应的 cookie 键值对(注意 getCookie 会按 path 过滤) */
    private fun cookieMap(url: String): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
        return raw.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
    }

    private fun checkLoginSuccess(): Boolean {
        val hasTGC = cookieMap("$CAS_ORIGIN/").containsKey("CASTGC")
        val hasXSRF = cookieMap("$ECODE_ORIGIN/").containsKey("XSRF-TOKEN")
        tvStatus.text = "当前: ${webView.url ?: "-"}\n" +
            "TGC(CASTGC): ${if (hasTGC) "已捕获 ✓" else "未捕获"} | " +
            "XSRF-TOKEN: ${if (hasXSRF) "已捕获 ✓" else "未捕获"}"
        if (hasTGC && hasXSRF) {
            onLoginSuccess("自动检测")
            return true
        }
        return false
    }

    private fun onLoginSuccess(reason: String) {
        if (!loginPhase) return
        loginPhase = false
        mainHandler.removeCallbacks(pollRunnable)

        val sb = StringBuilder()
        sb.append("═══ 登录成功(${reason}) ═══\n\n")
        sb.append("最终 URL:\n${webView.url ?: "-"}\n\n")
        // WebVPN 场景最终落在 webvpn 域,当前页面域一并纳入快照
        val origins = mutableListOf(CAS_ORIGIN, ECODE_ORIGIN, WEBVPN_HOST)
        webView.url?.let { u ->
            Regex("(https?://[^/]+)").find(u)?.groupValues?.get(1)?.let { origin ->
                if (origin !in origins) origins.add(origin)
            }
        }
        var foundAny = false
        for (origin in origins) {
            val cookies = cookieMap("$origin/")
            if (cookies.isEmpty()) continue
            foundAny = true
            sb.append("── $origin ──\n")
            for ((k, v) in cookies) {
                sb.append("  $k = ${if (v.length > 44) v.take(44) + "…" else v}\n")
            }
            sb.append('\n')
        }
        if (!foundAny) sb.append("(未捕获到任何 cookie)\n")
        tvResult.text = sb.toString()
        loginContainer.visibility = View.GONE
        successContainer.visibility = View.VISIBLE
        Toast.makeText(this, "登录成功:$reason", Toast.LENGTH_SHORT).show()
    }

    /** 用 WebView 拦截到的会话 cookie 实测一次受保护 API */
    private fun verifySession() {
        val finalUrl = webView.url ?: ""
        val viaWebvpn = finalUrl.startsWith(WEBVPN_HOST)
        // WebVPN 是路径包裹型网关:https://webvpn.neu.edu.cn/http(s)://真实地址
        val requestUrl = if (viaWebvpn) "$WEBVPN_HOST/$QR_API_URL" else QR_API_URL

        btnVerify.isEnabled = false
        appendResult("\n═══ 验证会话 ═══\n请求: $requestUrl\n")

        Thread {
            val result = try {
                val cookieHeader = CookieManager.getInstance().getCookie(requestUrl) ?: ""
                val xsrf = cookieMap("$ECODE_ORIGIN/")["XSRF-TOKEN"]
                    ?: cookieMap(requestUrl)["XSRF-TOKEN"]
                    ?: ""
                Log.d(TAG, "verify: cookieHeader=${cookieHeader.take(200)}, xsrf=${xsrf.take(16)}")
                val builder = Request.Builder().url(requestUrl)
                    .header("Cookie", cookieHeader)
                    .header("X-XSRF-TOKEN", xsrf)
                    .header("XSRF-TOKEN", xsrf)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                if (viaWebvpn) {
                    builder.header("Referer", finalUrl)
                } else {
                    builder.header("Referer", "https://ecode.neu.edu.cn/ecode/")
                        .header("Origin", "https://ecode.neu.edu.cn")
                }
                val resp = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(builder.get().build()).execute()
                resp.use { it.code to (it.body?.string()?.take(1500) ?: "") }
            } catch (e: Exception) {
                Log.e(TAG, "verify失败", e)
                -1 to "请求异常: ${e.message}"
            }

            runOnUiThread {
                btnVerify.isEnabled = true
                appendResult("HTTP ${result.first}\n${result.second}\n")
            }
        }.start()
    }

    private fun appendResult(text: String) {
        tvResult.append(text)
        successContainer.post { successContainer.fullScroll(View.FOCUS_DOWN) }
    }

    private fun restart() {
        loginPhase = false
        mainHandler.removeCallbacks(pollRunnable)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl("about:blank")
        tvResult.text = ""
        tvStatus.text = "已清除Cookie,等待开始…"
        successContainer.visibility = View.GONE
        loginContainer.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (loginPhase && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
