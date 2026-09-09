package com.neboer.ecode

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * WebView 登录:取代原表单提交模式(CasAuthenticator 已退役)。
 *
 * - 拉起 WebView 加载 CAS 登录页(service=ecode SSO 兑换接口),账密/短信验证/
 *   WebVPN 重写全部交给真实浏览器行为
 * - 轮询 CookieManager,ecode 域出现 XSRF-TOKEN 即登录成功(实测确认 ecode API
 *   会话只依赖它;CASTGC 在账密通过、短信未输入时就会种下,不能作判定依据)
 * - 会话有效的用户(含仅 CASTGC 还活着的情况)进来后会自动静默过票,无需输入
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val CAS_LOGIN_URL =
            "https://pass.neu.edu.cn/tpass/login?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"
        private const val POLL_INTERVAL_MS = 800L
    }

    private lateinit var webView: WebView
    private lateinit var tvLoginStatus: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loggedIn = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (loggedIn) return
            checkLoginSuccess()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // 冷启动快速通道:ecode 会话还在就直接进主页(会话服务端失效的话,
        // 主页 QR 拉取会失败并走静默续期/回登录页的既定流程)
        if (WebViewCookieJar.hasEcodeSession()) {
            Log.d(TAG, "已有ecode会话,直接进MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        webView = findViewById(R.id.webView)
        tvLoginStatus = findViewById(R.id.tvLoginStatus)

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

            // 重定向链中途 cookie 一落地就切屏,避开末尾无前端页面的错误页
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                Log.d(TAG, "history: $url")
                checkLoginSuccess()
            }
        }

        webView.loadUrl(CAS_LOGIN_URL)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun checkLoginSuccess() {
        if (loggedIn) return
        if (!WebViewCookieJar.hasEcodeSession()) return

        loggedIn = true
        mainHandler.removeCallbacks(pollRunnable)
        Log.d(TAG, "登录成功(XSRF-TOKEN已落地),跳转MainActivity")
        tvLoginStatus.visibility = View.VISIBLE
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finishAffinity()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
