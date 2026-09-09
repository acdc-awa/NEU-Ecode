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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
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
    private lateinit var cardQRCode: View
    private lateinit var btnSettings: ImageButton

    private var refreshJob: Job? = null
    private var balanceJob: Job? = null
    private var balanceSpin: ObjectAnimator? = null
    private var qrBitmap: Bitmap? = null
    private var qrVisible: Boolean = true
    private var originalBrightness: Float = -1f
    private var lastBackPressTime: Long = 0

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
    }

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

        qrVisible = settings.qrVisible
        applyQRVisibility()

        cardQRCode.setOnClickListener {
            qrVisible = !qrVisible
            settings.qrVisible = qrVisible
            applyQRVisibility()
        }

        btnRefreshBalance.setOnClickListener { loadBalance() }

        btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        startQRRefresh()
        loadBalance()
    }

    private fun startQRRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                if (!qrVisible) {
                    delay(1_000L)
                    continue
                }
                tvStatus.text = "正在获取二维码..."
                val result = withContext(Dispatchers.IO) {
                    apiClient.fetchQRCode()
                }

                if (result == null) {
                    Log.w(TAG, "fetchQRCode返回null,CASTGC失效,清空会话回登录页")
                    tvStatus.text = "登录已过期,请重新登录"
                    WebViewCookieJar.clearAll()
                    ivQRCode.setImageBitmap(null)
                    qrBitmap = null
                    val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    return@launch
                }

                tvStatus.text = "二维码有效"
                val bitmap = withContext(Dispatchers.Default) {
                    generateQRBitmap(result, 560)
                }
                qrBitmap = bitmap
                ivQRCode.setImageBitmap(bitmap)
                delay(10_000L)
            }
        }
    }

    /** 启动时和点按钮时拉一次余额(数据源按设置:ecard 权威值/portal JSON);进行中重复点击忽略,失败 Toast 提示 */
    private fun loadBalance() {
        if (balanceJob?.isActive == true) return
        val source: BalanceSource = when (settings.balanceSource) {
            BalanceSourceKind.PORTAL -> portalClient
            else -> ecardClient
        }
        balanceJob = lifecycleScope.launch {
            setBalanceRefreshing(true)
            val balance = withContext(Dispatchers.IO) {
                source.fetchBalance()
            }
            setBalanceRefreshing(false)
            if (balance != null) {
                tvBalance.text = getString(R.string.balance_format, balance)
            } else {
                Log.w(TAG, "余额获取失败(source=${settings.balanceSource})")
                Toast.makeText(this@MainActivity, R.string.balance_refresh_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setBalanceRefreshing(refreshing: Boolean) {
        btnRefreshBalance.isEnabled = !refreshing
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

    private fun applyQRVisibility() {
        if (qrVisible) {
            ivQRCode.visibility = View.VISIBLE
            layoutQRPlaceholder.visibility = View.GONE
            qrBitmap?.let { ivQRCode.setImageBitmap(it) }
            applyBrightness(true)
            startQRRefresh()
        } else {
            ivQRCode.visibility = View.GONE
            layoutQRPlaceholder.visibility = View.VISIBLE
            applyBrightness(false)
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
        if (on && qrVisible) {
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

    override fun onPause() {
        super.onPause()
        applyBrightness(false)
    }

    override fun onResume() {
        super.onResume()
        originalBrightness = readSystemBrightness()
        if (qrVisible) applyBrightness(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        balanceJob?.cancel()
        balanceSpin?.cancel()
    }
}
