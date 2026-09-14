package com.neboer.ecode

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * 网页版登录页:真 WebView 包着 CAS 登录页,**逃生口**,不是默认路径。
 *
 * 默认路径是 [LoginActivity] 的原生表单(与静默续期共用 [CasAuthClient])。这里留着是因为
 * 这个应用整体是屏幕抓取:校方一旦改版,原生解析可能整个失灵,而"登录"恰恰是唯一不能失灵
 * 的功能。两条入口:
 * - 原生面板报"打不开登录页/响应无法识别"时,面板上的"改用网页登录"
 * - 学校要求微信扫码这类原生表单做不了的验证方式时
 *
 * 判定成功的方式与原生路径**同源**:都要求服务端真的认这个会话,而不是"本地有 cookie"。
 * 它没有 ticket 可兑,所以先做一次便宜的预筛(本次 WebView 真的落到过 ecode 且 SESSION
 * cookie 已落地),再打一发 `qr-code` 真校验 —— 那个 SESSION 是约 100 天的持久 cookie,
 * 本地留着一条旧的就足以让"刚打开登录页"被判成成功,这正是旧版无限闪屏的根因。
 */
class WebLoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebLoginActivity"
        private const val ECODE_HOST = "ecode.neu.edu.cn"
        private const val POLL_INTERVAL_MS = 800L

        /**
         * 两次真校验之间的最小间隔。
         *
         * 轮询本身是 800ms 一次,而"落在 ecode 主机上但会话没被确认"可以是一个稳定状态
         * (比如那张票是无效的,服务端回了 500 页)。没有这道间隔,那种状态会变成每 800ms
         * 一发 qr-code 的连环请求。
         */
        private const val VERIFY_MIN_INTERVAL_MS = 3_000L

        /**
         * 这个 URL 是否真的落在 ecode 主机上。
         *
         * 绝不能用整串 `contains("ecode.neu.edu.cn")`:CAS 登录 URL 自己的查询串里就写着
         * `service=https%3A%2F%2Fecode.neu.edu.cn%2Fecode%2Fapi%2Fsso%2Flogin`(百分号编码
         * 只动 `:` 和 `/`,主机名的字母和点原样保留),于是"刚打开登录页"就会被判成"已经到过 ecode",
         * 配上本地那条 100 天寿命的旧 SESSION cookie,登录页会一次都没提交就自报成功。
         */
        private fun isEcodeUrl(url: String?): Boolean {
            val host = url?.let { Uri.parse(it).host } ?: return false
            return host == ECODE_HOST || host.endsWith(".$ECODE_HOST")
        }
    }

    private lateinit var webView: WebView
    private lateinit var cardLoginStatus: View
    private lateinit var progressLogin: LinearProgressIndicator

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loggedIn = false
    private var prefillDone = false

    /** 本次流程中 WebView 是否真的落到过 ecode(理由见类注释) */
    private var reachedEcode = false

    /** 有一发真校验在飞:轮询是 800ms 一次,不加它会对同一次登录反复发校验请求 */
    private var verifying = false

    /** 上次真校验的时刻,配合 [VERIFY_MIN_INTERVAL_MS] 兜住"落到了 ecode 但没通过"的稳态 */
    private var lastVerifyAt = 0L

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
        Log.d(TAG, "onCreate(网页登录)")
        setContentView(R.layout.activity_web_login)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootWebLoginLayout)) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            findViewById<View>(R.id.toolbarWebLogin).updatePadding(top = statusBarInsets.top)
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        webView = findViewById(R.id.webView)
        cardLoginStatus = findViewById(R.id.cardLoginStatus)
        progressLogin = findViewById(R.id.progressLogin)

        findViewById<MaterialToolbar>(R.id.toolbarWebLogin).setNavigationOnClickListener { navigateBack() }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        // OkHttp 与 WebView 用同一个 UA:登录日志里门户那条认证事件曾因截断 UA 被记成 unknown
        UserAgent.adoptWebViewUserAgent(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "onPageFinished: $url")
                if (isEcodeUrl(url)) reachedEcode = true
                prefillCredentials()
                checkLoginSuccess()
            }

            // 重定向链中途 cookie 一落地就切屏,避开末尾无前端页面的错误页
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                Log.d(TAG, "history: $url")
                if (isEcodeUrl(url)) {
                    if (!reachedEcode) Log.i(TAG, "WebView 已落到 ecode: $url")
                    reachedEcode = true
                }
                checkLoginSuccess()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressLogin.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progressLogin.progress = newProgress
            }
        }

        webView.loadUrl(CasAuthClient.ECODE_ENTRY)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun checkLoginSuccess() {
        if (loggedIn || verifying) return
        // 便宜的预筛一:本次必须真的落到过 ecode。否则会被本地那条陈旧 SESSION 骗过
        if (!reachedEcode) return
        // 预筛二:ecode 的 SESSION cookie 得在。两者都满足才有必要去问服务端
        if (!WebViewCookieJar.hasEcodeSession()) return

        val now = System.currentTimeMillis()
        if (now - lastVerifyAt < VERIFY_MIN_INTERVAL_MS) return
        lastVerifyAt = now

        // 但"cookie 在不在"证明不了服务端认这个会话(它是约 100 天的持久 cookie),而这一页
        // 与原生路径不同、没有 ticket 可兑 —— 所以用与原生路径同一条真校验:qr-code 200 才算过。
        // 请求必须离开主线程(这些回调都在主线程上跑)
        verifying = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    EcodeApiClient(AppHttp.client).hasAuthenticatedSession()
                } catch (e: IOException) {
                    Log.w(TAG, "登录结果校验网络异常", e)
                    false
                }
            }
            verifying = false
            if (ok) {
                onLoggedIn()
            } else {
                Log.i(TAG, "已落到 ecode,但会话未被服务端确认,继续等页面走完")
            }
        }
    }

    private fun onLoggedIn() {
        loggedIn = true
        mainHandler.removeCallbacks(pollRunnable)

        Log.i(TAG, "登录成功(真校验通过:qr-code 200),CLEAR_TOP回主界面自动刷新")
        cardLoginStatus.visibility = View.VISIBLE
        setResult(RESULT_OK)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    /** 预填:只填不提交,让用户自己按登录(校外时通常只需再补短信码) */
    private fun prefillCredentials() {
        if (prefillDone) return
        val creds = CredentialStore(this).load() ?: return
        webView.evaluateJavascript(prefillJs(creds)) { raw ->
            if (raw?.trim()?.removeSurrounding("\"") == "ok") {
                prefillDone = true
                Log.i(TAG, "已预填账号密码,用户只需补二次验证")
            }
        }
    }

    /**
     * 生成"填账密"的注入脚本(不提交)。取值一律经 [JSONObject.quote] 转义,
     * 避免密码里的引号/反斜杠把脚本打断。
     *
     * 设值时派发 input/change 事件:jQuery 的 .val() 读值本身不需要,但页面可能有别的监听者,
     * 顺手保持一致不至于踩坑。
     */
    private fun prefillJs(creds: CredentialStore.Credentials): String {
        val username = JSONObject.quote(creds.username)
        val password = JSONObject.quote(creds.password)
        return """
            (function(){
              try{
                var user=document.querySelector('#un')||document.querySelector('input[name="un"]')||
                         document.querySelector('input[name="username"]');
                var pass=document.querySelector('#pd')||document.querySelector('input[name="pd"]')||
                         document.querySelector('#password')||document.querySelector('input[name="password"]')||
                         document.querySelector('input[type="password"]');
                if(!user||!pass)return 'nofield';
                var set=function(el,v){
                  try{
                    var d=Object.getOwnPropertyDescriptor(el.__proto__,'value');
                    if(d&&d.set){d.set.call(el,v);}else{el.value=v;}
                  }catch(e){el.value=v;}
                  el.dispatchEvent(new Event('input',{bubbles:true}));
                  el.dispatchEvent(new Event('change',{bubbles:true}));
                };
                set(user,$username);
                set(pass,$password);
                if(!user.value||!pass.value)return 'novalue';
                return 'ok';
              }catch(e){return 'err';}
            })()
        """
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
