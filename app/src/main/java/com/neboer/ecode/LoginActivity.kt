package com.neboer.ecode

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val CAS_LOGIN_URL =
            "https://pass.neu.edu.cn/tpass/login?service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin"
        private const val POLL_INTERVAL_MS = 800L
    }

    private lateinit var webView: WebView
    private lateinit var cardLoginStatus: View
    private lateinit var progressLogin: LinearProgressIndicator

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
        setContentView(R.layout.activity_login)

        // 边到边沉浸式适配
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLoginLayout)) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            findViewById<View>(R.id.toolbarLogin).updatePadding(top = statusBarInsets.top)
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        webView = findViewById(R.id.webView)
        cardLoginStatus = findViewById(R.id.cardLoginStatus)
        progressLogin = findViewById(R.id.progressLogin)

        findViewById<MaterialToolbar>(R.id.toolbarLogin).setNavigationOnClickListener {
            navigateBack()
        }

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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressLogin.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progressLogin.progress = newProgress
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
        Log.d(TAG, "登录成功(XSRF-TOKEN已落地),CLEAR_TOP回主界面自动刷新")
        cardLoginStatus.visibility = View.VISIBLE
        setResult(RESULT_OK)
        // 主界面通常已在栈底(空态/设置页进入):清掉其上的设置/登录页直接回到它
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    /** 工具条关闭键与系统返回键同语义:WebView 可后退则后退,否则结束登录页 */
    private fun navigateBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        navigateBack()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
