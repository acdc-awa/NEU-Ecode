package com.neboer.ecode.webviewdemo

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebView 登录 CAS 测试 demo:
 * 1. 拉起 WebView 加载主端点 CAS 登录页(校外会自动重定向到 webvpn,无需特判,跟随即可)
 * 2. 用户在 WebView 里正常登录(账密/短信验证全部交给真实浏览器行为)
 * 3. CAS 302 到 service 兑换 ticket,ecode 种下 XSRF-TOKEN —— 这就是应用会话
 *    (实测确认:ecode API 只认 XSRF-TOKEN;TGC/CASTGC 仅是 CAS 的 SSO 凭证,作诊断展示)
 * 4. 检测到会话后切到结果页,自动请求 /ecode/api/qr-code 渲染真实二维码,
 *    按 qrInvalidTime 倒计时并在到期时自动刷新
 * 5. 所有 OkHttp 请求经 WebViewCookieJar 与 WebView 共享 cookie:
 *    ecard 余额链路靠 CASTGC 静默换票,无需账密
 */
class WebViewLoginDemoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewLoginDemo"

        /** 登录入口:TPass CAS 登录页,service 指向 ecode 的 SSO 兑换接口 */
        private const val CAS_LOGIN_URL =
            "https://pass.neu.edu.cn/tpass/login?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"
        private const val CAS_ORIGIN = "https://pass.neu.edu.cn"
        private const val ECODE_ORIGIN = "https://ecode.neu.edu.cn"
        private const val WEBVPN_HOST = "https://webvpn.neu.edu.cn"
        private const val QR_API_URL = "https://ecode.neu.edu.cn/ecode/api/qr-code"
        private const val POLL_INTERVAL_MS = 800L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvQrInfo: TextView
    private lateinit var ivQRCode: ImageView
    private lateinit var btnVerify: Button
    private lateinit var btnBalance: Button
    private lateinit var loginContainer: View
    private lateinit var successContainer: ScrollView

    /** 与 WebView 共享 cookie 的请求客户端 */
    private lateinit var apiClient: OkHttpClient

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loginPhase = false

    private var refreshRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var qrExpireAtMs = 0L

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

        apiClient = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        tvQrInfo = findViewById(R.id.tvQrInfo)
        ivQRCode = findViewById(R.id.ivQRCode)
        btnVerify = findViewById(R.id.btnVerify)
        btnBalance = findViewById(R.id.btnBalance)
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

            // 重定向链中途 cookie 一落地就能切屏,避免看到末尾的错误页
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                Log.d(TAG, "history: $url")
                if (loginPhase) checkLoginSuccess()
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startLogin() }
        findViewById<Button>(R.id.btnManualDone).setOnClickListener {
            if (!loginPhase) return@setOnClickListener
            // WebVPN 模式下 cookie 可能被网关改名/换域,自动检测不到时给人工兜底
            if (!checkLoginSuccess()) onLoginSuccess("手动标记")
        }
        btnVerify.setOnClickListener { verifySession() }
        btnBalance.setOnClickListener { testBalance() }
        findViewById<Button>(R.id.btnPortal).setOnClickListener { testPortalBalance() }
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
        cancelQrTimers()
        loginContainer.visibility = View.VISIBLE
        successContainer.visibility = View.GONE
        tvResult.text = ""
        tvQrInfo.visibility = View.GONE
        ivQRCode.visibility = View.GONE
        ivQRCode.setImageBitmap(null)
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
        // 实测:ecode 的 API 会话只依赖 XSRF-TOKEN,成功判定以它为准
        val hasXSRF = cookieMap("$ECODE_ORIGIN/").containsKey("XSRF-TOKEN")
        tvStatus.text = "当前: ${webView.url ?: "-"}\n" +
            "XSRF-TOKEN: ${if (hasXSRF) "已捕获 ✓" else "未捕获"} | TGC: ${findTGC()}"
        if (hasXSRF) {
            onLoginSuccess("自动检测")
            return true
        }
        return false
    }

    /** TGC(CASTGC) 诊断:可能种在 / 根路径或 /tpass 下,getCookie 按 path 匹配,逐处探测 */
    private fun findTGC(): String {
        for (probe in listOf("$CAS_ORIGIN/", "$CAS_ORIGIN/tpass/", CAS_LOGIN_URL)) {
            if (cookieMap(probe).containsKey("CASTGC")) {
                val path = probe.removePrefix("$CAS_ORIGIN")
                return "已捕获(path=$path) ✓"
            }
        }
        return "未捕获"
    }

    private fun onLoginSuccess(reason: String) {
        if (!loginPhase) return
        loginPhase = false
        mainHandler.removeCallbacks(pollRunnable)

        val sb = StringBuilder()
        sb.append("登录成功(${reason})\n\n最终 URL:\n${webView.url ?: "-"}\n\n")
        // WebVPN 场景最终落在 webvpn 域,当前页面域一并纳入快照
        val origins = mutableListOf(CAS_ORIGIN, ECODE_ORIGIN, WEBVPN_HOST)
        webView.url?.let { u ->
            Regex("(https?://[^/]+)").find(u)?.groupValues?.get(1)?.let { origin ->
                if (origin !in origins) origins.add(origin)
            }
        }
        var foundAny = false
        for (origin in origins) {
            val cookies = cookieMap("$origin/").toMutableMap()
            // CAS 的会话 cookie 可能带 /tpass 路径,合并进来
            if (origin == CAS_ORIGIN) cookies.putAll(cookieMap("$CAS_ORIGIN/tpass/"))
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

        // 成功即自动验证会话,渲染真实二维码
        verifySession()
    }

    /**
     * 用 WebView 会话实测 qr-code 接口。cookie 由 WebViewCookieJar 按域自动供给,
     * 校外时主端点重定向到 webvpn 也无需特判(已实测,跟随重定向即可)。
     */
    private fun verifySession() {
        btnVerify.isEnabled = false
        tvQrInfo.visibility = View.GONE
        ivQRCode.visibility = View.GONE
        appendResult("\n═══ 请求二维码 ═══\nGET $QR_API_URL(cookie由CookieManager按域供给,重定向自动跟随)\n")

        Thread {
            val outcome = try {
                val xsrf = cookieMap("$ECODE_ORIGIN/")["XSRF-TOKEN"]
                    ?: cookieMap(QR_API_URL)["XSRF-TOKEN"] ?: ""
                val request = Request.Builder().url(QR_API_URL)
                    .header("X-XSRF-TOKEN", xsrf)
                    .header("XSRF-TOKEN", xsrf)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://ecode.neu.edu.cn/ecode/")
                    .get().build()
                apiClient.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: Exception) {
                Log.e(TAG, "verify失败", e)
                -1 to "请求异常: ${e.message}"
            }

            // 工作线程里解析响应并生成二维码位图,再回主线程刷新 UI
            val (code, body) = outcome
            val payload = if (code == 200) parseQr(body) else null
            val bitmap = payload?.qrCode?.let { renderQr(it) }

            runOnUiThread {
                btnVerify.isEnabled = true
                appendResult("HTTP $code\n")
                if (payload != null && bitmap != null) {
                    ivQRCode.setImageBitmap(bitmap)
                    ivQRCode.visibility = View.VISIBLE
                    tvQrInfo.visibility = View.VISIBLE
                    appendResult("${payload.summary}\n")
                    scheduleQrAutoRefresh(payload.invalidMs)
                } else {
                    appendResult("${body.take(1500)}\n")
                }
            }
        }.start()
    }

    /** 余额链路:不带账密,只靠 CASTGC 静默换票 */
    private fun testBalance() {
        btnBalance.isEnabled = false
        appendResult("\n═══ 余额验证(ecard,纯TGC静默换票) ═══\n")
        Thread {
            val result = try {
                EcardTester(apiClient).fetchBalance()
            } catch (e: Exception) {
                Log.e(TAG, "余额验证异常", e)
                "异常: ${e.message}"
            }
            runOnUiThread {
                btnBalance.isEnabled = true
                appendResult("$result\n")
            }
        }.start()
    }

    /** 门户 JSON 余额链路:同样纯 CASTGC 兑票,是后续替代 ecard 的候选数据源 */
    private fun testPortalBalance() {
        findViewById<Button>(R.id.btnPortal).isEnabled = false
        appendResult("\n═══ 余额验证(portal JSON,纯TGC兑票) ═══\n")
        Thread {
            val result = try {
                PortalTester(apiClient).fetchBalance()
            } catch (e: Exception) {
                Log.e(TAG, "门户余额验证异常", e)
                "异常: ${e.message}"
            }
            runOnUiThread {
                findViewById<Button>(R.id.btnPortal).isEnabled = true
                appendResult("$result\n")
            }
        }.start()
    }

    private class QrPayload(
        val qrCode: String?,
        val createMs: Long,
        val invalidMs: Long,
        val summary: String,
    )

    /** 解析 data[0].attributes:qrCode / createTime / qrInvalidTime(unix秒级时间戳) */
    private fun parseQr(body: String): QrPayload? {
        val attrs = try {
            JSONObject(body).getJSONArray("data").getJSONObject(0)
                .getJSONObject("attributes")
        } catch (e: Exception) {
            Log.e(TAG, "attributes解析失败", e)
            return null
        }
        val qr = attrs.optString("qrCode").ifEmpty { null }
        val create = attrs.optLong("createTime")
        val invalid = attrs.optLong("qrInvalidTime")
        val summary = "createTime:    ${fmtUnix(create)}\n" +
            "qrInvalidTime: ${fmtUnix(invalid)}"
        return QrPayload(qr, toMs(create), toMs(invalid), summary)
    }

    private fun toMs(ts: Long): Long = if (ts > 10_000_000_000L) ts else ts * 1000

    private fun fmtUnix(ts: Long): String {
        if (ts <= 0) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(toMs(ts)))
    }

    /** 生成二维码位图;失败返回 null */
    private fun renderQr(content: String): Bitmap? {
        return try {
            val size = 512
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "二维码渲染失败", e)
            null
        }
    }

    /** 按 qrInvalidTime 倒计时,到期自动重新拉取二维码 */
    private fun scheduleQrAutoRefresh(invalidMs: Long) {
        cancelQrTimers()
        if (invalidMs <= System.currentTimeMillis()) {
            verifySession()
            return
        }
        qrExpireAtMs = invalidMs
        countdownRunnable = object : Runnable {
            override fun run() {
                val remainSec = (qrExpireAtMs - System.currentTimeMillis()) / 1000
                val expireText = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(qrExpireAtMs))
                tvQrInfo.text = if (remainSec > 0) {
                    "有效期至 $expireText · 剩余 ${remainSec}s · 到期自动刷新"
                } else {
                    "二维码已到期,刷新中…"
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        countdownRunnable?.let { mainHandler.post(it) }
        refreshRunnable = Runnable { verifySession() }
        mainHandler.postDelayed(
            refreshRunnable!!,
            (qrExpireAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 500
        )
    }

    private fun cancelQrTimers() {
        refreshRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        refreshRunnable = null
        countdownRunnable = null
    }

    private fun appendResult(text: String) {
        tvResult.append(text)
        successContainer.post { successContainer.fullScroll(View.FOCUS_DOWN) }
    }

    private fun restart() {
        loginPhase = false
        mainHandler.removeCallbacks(pollRunnable)
        cancelQrTimers()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl("about:blank")
        tvResult.text = ""
        tvQrInfo.visibility = View.GONE
        ivQRCode.visibility = View.GONE
        ivQRCode.setImageBitmap(null)
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
