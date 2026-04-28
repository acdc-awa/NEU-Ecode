package com.neboer.ecode

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
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

    private lateinit var credentialManager: CredentialManager
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var apiClient: EcodeApiClient
    private lateinit var casAuthenticator: CasAuthenticator
    private lateinit var settings: AppSettings

    private lateinit var tvUsername: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivQRCode: ImageView
    private lateinit var layoutQRPlaceholder: View
    private lateinit var cardQRCode: View
    private lateinit var btnSettings: ImageButton

    private var refreshJob: Job? = null
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
        ivQRCode = findViewById(R.id.ivQRCode)
        layoutQRPlaceholder = findViewById(R.id.layoutQRPlaceholder)
        cardQRCode = findViewById(R.id.cardQRCode)
        btnSettings = findViewById(R.id.btnSettings)

        credentialManager = CredentialManager(this)
        cookieJar = PersistentCookieJar(this)
        settings = AppSettings(this)

        originalBrightness = readSystemBrightness()

        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()

        casAuthenticator = CasAuthenticator(okHttpClient, credentialManager)
        apiClient = EcodeApiClient(okHttpClient, credentialManager, casAuthenticator)

        tvUsername.text = credentialManager.getUsername()

        qrVisible = settings.qrVisible
        applyQRVisibility()

        cardQRCode.setOnClickListener {
            qrVisible = !qrVisible
            settings.qrVisible = qrVisible
            applyQRVisibility()
        }

        btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        startQRRefresh()
    }

    private fun startQRRefresh() {
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                tvStatus.text = "正在获取二维码..."
                val result = withContext(Dispatchers.IO) {
                    apiClient.fetchQRCode()
                }

                if (result == null) {
                    Log.w(TAG, "fetchQRCode返回null，认证失败，清空凭据回登录页")
                    tvStatus.text = "认证失败，请重新登录"
                    cookieJar.clear()
                    credentialManager.clear()
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
                if (qrVisible) ivQRCode.setImageBitmap(bitmap)
                delay(10_000L)
            }
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
            "single" -> finish()
            else -> {
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
    }
}
