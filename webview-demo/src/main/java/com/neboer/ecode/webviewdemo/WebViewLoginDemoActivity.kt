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
 * 1. 拉起 WebView 加载 CAS 登录页(service 指向 ecode 的 SSO 兑换接口)
 * 2. 用户在 WebView 里正常登录(账密/短信验证/WebVPN 重定向全部交给真实浏览器行为)
 * 3. CAS 302 到 service 兑换 ticket,ecode 种下 XSRF-TOKEN —— 这就是应用会话
 *    (实测确认:ecode API 只认 XSRF-TOKEN;TGC/CASTGC 仅是 CAS 的 SSO 凭证,作诊断展示)
 * 4. 检测到会话后切到结果页,自动请求 /ecode/api/qr-code 渲染真实二维码 + 有效期
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
    private lateinit var tvQrInfo: TextView
    private lateinit var ivQRCode: ImageView
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
        tvQrInfo = findViewById(R.id.tvQrInfo)
        ivQRCode = findViewById(R.id.ivQRCode)
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

    /** 用 WebView 拦截到的会话 cookie 实测受保护 API,并渲染二维码 */
    private fun verifySession() {
        val finalUrl = webView.url ?: ""
        val viaWebvpn = finalUrl.startsWith(WEBVPN_HOST)
        // WebVPN 是路径包裹型网关:https://webvpn.neu.edu.cn/http(s)://真实地址
        val requestUrl = if (viaWebvpn) "$WEBVPN_HOST/$QR_API_URL" else QR_API_URL

        btnVerify.isEnabled = false
        tvQrInfo.visibility = View.GONE
        ivQRCode.visibility = View.GONE
        appendResult("\n═══ 验证会话 ═══\n请求: $requestUrl\n")

        Thread {
            val outcome = try {
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
                resp.use { it.code to (it.body?.string() ?: "") }
            } catch (e: Exception) {
                Log.e(TAG, "verify失败", e)
                -1 to "请求异常: ${e.message}"
            }

            // 工作线程里解析响应并生成二维码位图,再回主线程刷新 UI
            val (code, body) = outcome
            val qrBitmap = if (code == 200) parseQrAndRender(body) else null
            val summary = if (code == 200) summarizeQr(body) else body.take(1500)

            runOnUiThread {
                btnVerify.isEnabled = true
                appendResult("HTTP $code\n")
                if (qrBitmap != null) {
                    ivQRCode.setImageBitmap(qrBitmap)
                    ivQRCode.visibility = View.VISIBLE
                    tvQrInfo.visibility = View.VISIBLE
                }
                appendResult("$summary\n")
            }
        }.start()
    }

    /** 解析 data[0].attributes.qrCode 生成位图;失败返回 null */
    private fun parseQrAndRender(body: String): Bitmap? {
        val qr = try {
            JSONObject(body).getJSONArray("data").getJSONObject(0)
                .getJSONObject("attributes").getString("qrCode")
        } catch (e: Exception) {
            Log.e(TAG, "qrCode解析失败", e)
            return null
        }
        return try {
            val size = 512
            val bitMatrix = QRCodeWriter().encode(qr, BarcodeFormat.QR_CODE, size, size)
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

    /** 汇总有效期:attributes.createTime / qrInvaildTime 是 unix 时间戳(自动识别秒/毫秒) */
    private fun summarizeQr(body: String): String {
        return try {
            val attrs = JSONObject(body).getJSONArray("data").getJSONObject(0).getJSONObject("attributes")
            val create = attrs.optLong("createTime")
            val invalid = attrs.optLong("qrInvaildTime")
            val sb = StringBuilder("二维码已生成 ✓\n")
            sb.append("createTime:    ${fmtUnix(create)}\n")
            sb.append("qrInvaildTime: ${fmtUnix(invalid)}")
            if (create > 0 && invalid > create) {
                val unit = if (invalid > 10_000_000_000L) 1000L else 1L
                val now = System.currentTimeMillis() / 1000 * 1000
                val remainSec = (invalid * unit - now) / 1000
                sb.append("(剩余 ${remainSec} 秒)")
            }
            sb.toString()
        } catch (e: Exception) {
            "响应体(未解析出attributes): ${body.take(800)}"
        }
    }

    private fun fmtUnix(ts: Long): String {
        if (ts <= 0) return "-"
        val ms = if (ts > 10_000_000_000L) ts else ts * 1000
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
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
