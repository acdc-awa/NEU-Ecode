package com.neboer.ecode

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** qrInvalidTime 到期后延迟该毫秒数再拉新码 */
        private const val QR_EXPIRE_BUFFER_MS = 500L

        /** 服务端未给 qrInvalidTime 时的兜底轮询间隔 */
        private const val QR_FALLBACK_INTERVAL_MS = 10_000L

        /** 网络异常重试:首次 5s,翻倍,上限 30s */
        private const val NET_RETRY_BASE_MS = 5_000L
        private const val NET_RETRY_MAX_MS = 30_000L
    }

    private lateinit var apiClient: EcodeApiClient
    private lateinit var ecardClient: EcardClient
    private lateinit var portalClient: PortalClient
    private lateinit var settings: AppSettings

    private lateinit var tvUsername: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBalance: TextView
    private lateinit var btnRefreshBalance: ImageButton
    private lateinit var ivQRCode: ImageView
    private lateinit var layoutQRPlaceholder: View
    private lateinit var tvPlaceholderText: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var cardQRCode: View
    private lateinit var btnSettings: ImageButton

    private var refreshJob: Job? = null
    private var balanceJob: Job? = null
    private var balanceSpin: ObjectAnimator? = null
    private var qrBitmap: Bitmap? = null
    private var qrVisible: Boolean = true
    private var originalBrightness: Float = -1f
    private var lastBackPressTime: Long = 0

    /** UI 层登录态:决定主界面显示数据还是空态(会话唯一来源仍是 CookieManager) */
    private var loggedIn: Boolean = false

    /** 本次会话是否已自动拉过一次余额(登录成功回主界面刷一次,之后仅手动) */
    private var balanceAutoLoaded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_main)

        tvUsername = findViewById(R.id.tvUsername)
        tvStatus = findViewById(R.id.tvStatus)
        tvBalance = findViewById(R.id.tvBalance)
        btnRefreshBalance = findViewById(R.id.btnRefreshBalance)
        ivQRCode = findViewById(R.id.ivQRCode)
        layoutQRPlaceholder = findViewById(R.id.layoutQRPlaceholder)
        tvPlaceholderText = findViewById(R.id.tvPlaceholderText)
        btnLogin = findViewById(R.id.btnLogin)
        cardQRCode = findViewById(R.id.cardQRCode)
        btnSettings = findViewById(R.id.btnSettings)

        settings = AppSettings(this)

        originalBrightness = readSystemBrightness()

        // 会话唯一来源 = CookieManager(WebView 登录种下),OkHttp 经 WebViewCookieJar 共享
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .build()

        apiClient = EcodeApiClient(okHttpClient)
        ecardClient = EcardClient(okHttpClient)
        portalClient = PortalClient(okHttpClient)

        tvUsername.text = getString(R.string.app_name)

        btnRefreshBalance.setOnClickListener { loadBalance() }
        btnLogin.setOnClickListener { openLogin() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        cardQRCode.setOnClickListener {
            // 未登录空态下点卡片无意义,不进入隐藏/显示切换
            if (!loggedIn) return@setOnClickListener
            qrVisible = !qrVisible
            settings.qrVisible = qrVisible
            applyQRVisibility()
            // 重新显示时立即重启刷新拉新码(隐藏时 applyQRVisibility 已停掉循环)
            if (qrVisible) startQRRefresh()
        }

        qrVisible = settings.qrVisible
        loggedIn = WebViewCookieJar.hasEcodeSession()
        renderSessionState()
    }

    /** 按登录态渲染整个主界面:已登录 = 二维码/余额数据态;未登录 = 空态 + 去登录按钮 */
    private fun renderSessionState() {
        if (loggedIn) {
            tvStatus.text = getString(R.string.fetching_qr)
            tvBalance.text = getString(R.string.balance_unknown)
            btnRefreshBalance.isEnabled = true
            applyQRVisibility()
        } else {
            refreshJob?.cancel()
            refreshJob = null
            balanceJob?.cancel()
            balanceJob = null
            balanceAutoLoaded = false
            setBalanceRefreshing(false)
            tvStatus.text = getString(R.string.status_not_logged_in)
            tvBalance.text = getString(R.string.balance_unknown)
            btnRefreshBalance.isEnabled = false
            qrBitmap = null
            ivQRCode.setImageBitmap(null)
            ivQRCode.visibility = View.GONE
            layoutQRPlaceholder.visibility = View.VISIBLE
            tvPlaceholderText.setText(R.string.not_logged_in_placeholder)
            btnLogin.visibility = View.VISIBLE
            applyBrightness(false)
        }
    }

    /** 空态"去登录"按钮:拉起 WebView 登录页,成功后登录页会 CLEAR_TOP 回到这里 */
    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        originalBrightness = readSystemBrightness()

        // 从登录页/设置页回来:登录态可能已变化(WebView 种下 XSRF-TOKEN 或被切换账号清空)
        val hasSession = WebViewCookieJar.hasEcodeSession()
        if (hasSession != loggedIn) {
            Log.d(TAG, "登录态变化: $loggedIn -> $hasSession")
            loggedIn = hasSession
            renderSessionState()
        }

        // 刷新只在前台 + 二维码可见时进行;回前台立即重启循环拉新码
        if (loggedIn && qrVisible && refreshJob?.isActive != true) {
            startQRRefresh()
        }
        if (qrVisible) applyBrightness(true)

        // 登录成功回主界面自动刷一次余额,之后保持手动
        if (loggedIn && !balanceAutoLoaded) {
            balanceAutoLoaded = true
            loadBalance()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退后台/回桌面即停止二维码刷新(程序留在后台也不发请求)
        refreshJob?.cancel()
        refreshJob = null
        applyBrightness(false)
    }

    /**
     * 前台 + 二维码可见期间的刷新循环:成功后按服务端 qrInvalidTime 定下次拉取时刻
     * (字段缺失退回 10s 兜底);网络异常退避重试;会话失效则停在空态等用户重新登录
     */
    private fun startQRRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var netRetryMs = NET_RETRY_BASE_MS
            while (isActive) {
                tvStatus.text = getString(R.string.fetching_qr)
                val result = withContext(Dispatchers.IO) { apiClient.fetchQRCode() }

                when (result) {
                    is QrFetchResult.Success -> {
                        netRetryMs = NET_RETRY_BASE_MS
                        val bitmap = withContext(Dispatchers.Default) {
                            generateQRBitmap(result.qrCode, 560)
                        }
                        qrBitmap = bitmap
                        ivQRCode.setImageBitmap(bitmap)
                        tvStatus.text = result.invalidAtMs?.let {
                            getString(R.string.qr_valid_until, formatClock(it))
                        } ?: getString(R.string.qr_valid_no_expiry)

                        val nextDelay = result.invalidAtMs
                            ?.let {
                                (it + QR_EXPIRE_BUFFER_MS - System.currentTimeMillis())
                                    .coerceIn(1_000L, 120_000L)
                            }
                            ?: QR_FALLBACK_INTERVAL_MS
                        Log.d(TAG, "二维码已刷新,${nextDelay}ms后拉取下一张")
                        delay(nextDelay)
                    }

                    QrFetchResult.AuthExpired -> {
                        Log.w(TAG, "CASTGC失效且续期失败,清会话停在空态等待重新登录")
                        WebViewCookieJar.clearAll()
                        loggedIn = false
                        renderSessionState()
                        tvStatus.text = getString(R.string.login_expired)
                        return@launch
                    }

                    QrFetchResult.NetworkError -> {
                        tvStatus.text = getString(R.string.network_retry)
                        delay(netRetryMs)
                        netRetryMs = (netRetryMs * 2).coerceAtMost(NET_RETRY_MAX_MS)
                    }
                }
            }
        }
    }

    private fun formatClock(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ms))

    /** 启动时和点按钮时拉一次余额(数据源按设置:ecard 权威值/portal JSON);进行中重复点击忽略 */
    private fun loadBalance() {
        if (!loggedIn) return
        if (balanceJob?.isActive == true) return
        val source: BalanceSource = when (settings.balanceSource) {
            BalanceSourceKind.PORTAL -> portalClient
            else -> ecardClient
        }
        balanceJob = lifecycleScope.launch {
            setBalanceRefreshing(true)
            val result = withContext(Dispatchers.IO) {
                source.fetchBalance()
            }
            setBalanceRefreshing(false)
            when (result) {
                is BalanceResult.Success ->
                    tvBalance.text = getString(R.string.balance_format, result.value)

                BalanceResult.NetworkUnreachable -> {
                    Log.w(TAG, "余额网络不可达(source=${settings.balanceSource})")
                    val msgRes = if (settings.balanceSource == BalanceSourceKind.PORTAL) {
                        R.string.balance_network_unreachable
                    } else {
                        R.string.balance_ecard_unreachable
                    }
                    Toast.makeText(this@MainActivity, msgRes, Toast.LENGTH_LONG).show()
                }

                BalanceResult.Failed -> {
                    Log.w(TAG, "余额获取失败(source=${settings.balanceSource})")
                    Toast.makeText(this@MainActivity, R.string.balance_refresh_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun setBalanceRefreshing(refreshing: Boolean) {
        btnRefreshBalance.isEnabled = !refreshing && loggedIn
        if (refreshing) {
            balanceSpin = ObjectAnimator.ofFloat(btnRefreshBalance, View.ROTATION, 0f, 360f).apply {
                duration = 1_000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            balanceSpin?.cancel()
            balanceSpin = null
            btnRefreshBalance.rotation = 0f
        }
    }

    private fun generateQRBitmap(content: String, size: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** 已登录时二维码显示/隐藏(隐藏 = 停刷新;显示 = 由调用方重启刷新拉新码) */
    private fun applyQRVisibility() {
        if (qrVisible) {
            ivQRCode.visibility = View.VISIBLE
            layoutQRPlaceholder.visibility = View.GONE
            btnLogin.visibility = View.GONE
            qrBitmap?.let { ivQRCode.setImageBitmap(it) }
            applyBrightness(true)
        } else {
            ivQRCode.visibility = View.GONE
            layoutQRPlaceholder.visibility = View.VISIBLE
            btnLogin.visibility = View.GONE
            tvPlaceholderText.setText(R.string.qr_hidden_placeholder)
            applyBrightness(false)
            refreshJob?.cancel()
            refreshJob = null
        }
    }

    private fun readSystemBrightness(): Float {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun applyBrightness(on: Boolean) {
        if (on && qrVisible && loggedIn) {
            window.attributes = window.attributes.apply {
                screenBrightness = 1.0f
            }
        } else {
            window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness
            }
        }
    }

    override fun onBackPressed() {
        when (settings.backPressMode) {
            BackPressMode.SINGLE -> finish()
            BackPressMode.DOUBLE -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this, R.string.back_press_again, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        balanceJob?.cancel()
        balanceSpin?.cancel()
    }
}
