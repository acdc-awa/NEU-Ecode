package com.neboer.ecode

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "SettingsActivity"
    }

    /** 更新页 UI 状态机 */
    private enum class UpdateUiState {
        IDLE, CHECKING, CHECK_FAILED, UP_TO_DATE, AVAILABLE,
        DOWNLOADING, PAUSED, FAILED, DOWNLOADED,
    }

    private lateinit var downloader: ApkUpdateDownloader

    private var currentVersion = ""
    private var latestRelease: ReleaseInfo? = null
    private var uiState = UpdateUiState.IDLE
    private var lastMessage = "" // CHECK_FAILED / FAILED 时的错误信息
    private var speedEma = 0.0 // 下载速度指数平滑(B/s)
    private var lastProgressBytes = 0L
    private var lastProgressTime = 0L

    // 视图引用
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvChangelog: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnUpdateAction: MaterialButton
    private lateinit var btnAccount: MaterialButton
    private lateinit var tvAccountStatus: TextView

    private val downloadListener = object : ApkUpdateDownloader.Listener {
        // 回调已由下载器投递到主线程
        override fun onProgress(bytes: Long, total: Long) {
            val release = latestRelease ?: return
            val size = if (total > 0) total else release.apkSize
            val now = System.currentTimeMillis()
            if (lastProgressTime > 0L && now > lastProgressTime) {
                val speed = (bytes - lastProgressBytes) * 1000.0 / (now - lastProgressTime)
                speedEma = if (speedEma == 0.0) speed else speedEma * 0.7 + speed * 0.3
            }
            lastProgressBytes = bytes
            lastProgressTime = now

            if (size > 0) {
                progressDownload.isIndeterminate = false
                progressDownload.progress = ((bytes * 100) / size).toInt().coerceIn(0, 100)
            } else {
                progressDownload.isIndeterminate = true
            }
            tvProgress.text = buildString {
                if (size > 0) {
                    append("${formatBytes(bytes)} / ${formatBytes(size)}")
                    append(" · ${(bytes * 100 / size).coerceAtMost(100)}%")
                } else {
                    append(formatBytes(bytes))
                }
                if (speedEma > 0) append(" · ${formatBytes(speedEma.toLong())}/s")
            }
        }

        override fun onCompleted(apkFile: File) {
            uiState = UpdateUiState.DOWNLOADED
            renderUpdateUi()
        }

        override fun onFailed(message: String) {
            lastMessage = message
            uiState = UpdateUiState.FAILED
            renderUpdateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 边到边沉浸式适配
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootSettingsLayout)) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            findViewById<View>(R.id.toolbarSettings).updatePadding(top = statusBarInsets.top)
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbarSettings).setNavigationOnClickListener {
            finish()
        }

        val settings = AppSettings(this)

        val radioSingle: MaterialRadioButton = findViewById(R.id.radioSingle)
        val radioDouble: MaterialRadioButton = findViewById(R.id.radioDouble)
        radioSingle.isChecked = settings.backPressMode == BackPressMode.SINGLE
        radioDouble.isChecked = settings.backPressMode == BackPressMode.DOUBLE

        findViewById<RadioGroup>(R.id.radioGroupBackMode).setOnCheckedChangeListener { _, id ->
            settings.backPressMode = when (id) {
                R.id.radioSingle -> BackPressMode.SINGLE
                else -> BackPressMode.DOUBLE
            }
        }

        // 账号卡片统一管"登录状态 + 自动续期状态"。自动续期没有自己的开关:它就是登录页上
        // 那个"保存账号密码"勾选框,这里只把结果显示出来 —— 一个功能只有一个开关
        tvAccountStatus = findViewById(R.id.tvAccountStatus)
        btnAccount = findViewById(R.id.btnSwitchAccount)
        btnAccount.setOnClickListener { confirmAccountAction() }
        findViewById<ImageButton>(R.id.btnHelpAccount).setOnClickListener {
            showHelpDialog(R.string.help_credential_title, R.string.help_credential_message)
        }

        // 二维码高亮方式: 全屏最高亮度(默认,传统) / HDR局部高亮(实验性) / 跟随系统亮度
        val ddlQrBrightness: MaterialAutoCompleteTextView = findViewById(R.id.ddlQrBrightness)
        val qrBrightnessLabels = listOf(
            getString(R.string.qr_brightness_full),
            getString(R.string.qr_brightness_hdr),
            getString(R.string.qr_brightness_system),
        )
        val qrBrightnessModes = listOf(
            QrBrightnessMode.FULL,
            QrBrightnessMode.HDR,
            QrBrightnessMode.SYSTEM,
        )
        ddlQrBrightness.setSimpleItems(qrBrightnessLabels.toTypedArray())
        val selectedIndex = qrBrightnessModes.indexOf(settings.qrBrightnessMode).coerceAtLeast(0)
        ddlQrBrightness.setText(qrBrightnessLabels[selectedIndex], false)
        ddlQrBrightness.setOnItemClickListener { _, _, position, _ ->
            settings.qrBrightnessMode = qrBrightnessModes[position]
            Log.i(TAG, "二维码高亮方式切换为: ${qrBrightnessLabels[position]}")
        }

        // 开屏二维码显示: 打开应用时直接显示还是先隐藏(会话内点卡片切换不持久化,开屏仍按此偏好)
        val ddlQrLaunch: MaterialAutoCompleteTextView = findViewById(R.id.ddlQrLaunchVisibility)
        val qrLaunchLabels = listOf(
            getString(R.string.qr_launch_show),
            getString(R.string.qr_launch_hide),
        )
        ddlQrLaunch.setSimpleItems(qrLaunchLabels.toTypedArray())
        ddlQrLaunch.setText(
            if (settings.qrShowOnLaunch) qrLaunchLabels[0] else qrLaunchLabels[1],
            false
        )
        ddlQrLaunch.setOnItemClickListener { _, _, position, _ ->
            settings.qrShowOnLaunch = position == 0
            Log.i(TAG, "开屏二维码显示切换为: ${qrLaunchLabels[position]}")
        }

        currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.settings_version, currentVersion)

        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvChangelog = findViewById(R.id.tvChangelog)
        progressDownload = findViewById(R.id.progressDownload)
        tvProgress = findViewById(R.id.tvProgress)
        btnUpdateAction = findViewById(R.id.btnUpdateAction)

        downloader = ApkUpdateDownloader(this)
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        btnUpdateAction.setOnClickListener {
            when (uiState) {
                UpdateUiState.AVAILABLE, UpdateUiState.PAUSED, UpdateUiState.FAILED -> startDownload()
                UpdateUiState.DOWNLOADING -> pauseDownload()
                UpdateUiState.DOWNLOADED -> installApk()
                else -> Unit
            }
        }

        // 绑定各个 [?] 极简问号说明按钮
        findViewById<ImageButton>(R.id.btnHelpQrBrightness).setOnClickListener {
            showHelpDialog(R.string.help_qr_brightness_title, R.string.help_qr_brightness_message)
        }
        findViewById<ImageButton>(R.id.btnHelpQrLaunch).setOnClickListener {
            showHelpDialog(R.string.help_qr_launch_title, R.string.help_qr_launch_message)
        }
        findViewById<ImageButton>(R.id.btnHelpBackMode).setOnClickListener {
            showHelpDialog(R.string.help_back_mode_title, R.string.help_back_mode_message)
        }
    }

    private fun showHelpDialog(titleRes: Int, messageRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.help_action_got_it, null)
            .show()
    }

    /**
     * 账号按钮 = 登录 / 切换账号。**切换账号是唯一会"清除所有"的动作**:会话 cookie 与
     * 已保存的账号密码一起清掉,所以它是真正换一个人的出口。因为它现在还会连带关掉自动续期,
     * 所以先确认一次再做。
     *
     * 未登录时按钮就是普通登录,没什么可清的,直接进登录页。
     */
    private fun confirmAccountAction() {
        if (!WebViewCookieJar.hasEcodeSession()) {
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.switch_account_confirm_title)
            .setMessage(R.string.switch_account_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_switch_account) { _, _ ->
                Log.i(TAG, "切换账号:清除全部会话与已保存的账号密码")
                WebViewCookieJar.clearAll()
                CredentialStore(this).clear()
                startActivity(Intent(this, LoginActivity::class.java))
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"授权页回来后续上安装
        ApkInstaller.resumePendingInstall(this)
        renderAccountState()
    }

    /**
     * 账号卡片:(登录状态 · 自动续期状态) + 一个动作按钮。
     *
     * 只有状态、没有开关:自动续期在实现上就是"登录时勾选了保存账号密码",开关就在登录页那个
     * 勾选框上。再放一个"开启/关闭自动续期"按钮,等于给同一件事开两个入口 —— 而且那个入口
     * 在"本地 TGT 还活着"时根本走不到输入账密那一步,按下去看着就是坏的。
     * 账号名可以显示,密码永不外露。
     */
    private fun renderAccountState() {
        val loggedIn = WebViewCookieJar.hasEcodeSession()
        // load() 会解密一次密码,所以先取一份;username() 单独读是为了兜住"密码已被熔断清掉、
        // 只剩账号名"那种状态(那时自动续期是关的,但账号名还能用)
        val store = CredentialStore(this)
        val creds = store.load()
        val autoRenew = creds != null
        val savedUser = creds?.username ?: store.username()

        val loginText = getString(if (loggedIn) R.string.account_logged_in else R.string.account_not_logged_in)
        val renewText = when {
            autoRenew -> getString(R.string.account_auto_renew_on, savedUser.orEmpty())
            else -> getString(R.string.account_auto_renew_off)
        }
        // 未登录但开着自动续期 = 上一次自动登录没成功,把原因说出来,否则用户只会觉得"没登录"
        val reason = if (!loggedIn && autoRenew) autoRenewFailureReason() else null
        tvAccountStatus.text = if (reason == null) {
            getString(R.string.account_status, loginText, renewText)
        } else {
            getString(R.string.account_status_with_reason, loginText, renewText, reason)
        }
        tvAccountStatus.setTextColor(
            getColor(if (loggedIn) R.color.status_pill_text else R.color.md_theme_on_surface_variant)
        )

        btnAccount.text = getString(
            if (loggedIn) R.string.settings_switch_account else R.string.settings_login
        )
    }

    /**
     * 上一次自动登录为什么没成(只解释"需要人工介入"的两种)。
     *
     * 这是原来诊断框里那行"上次结果"里唯一对用户有意义的部分:静默续期失败后界面只会变成
     * "未登录",而原因(要验证码 / 密码被拒)决定了用户该做什么。
     */
    private fun autoRenewFailureReason(): String? = when (SilentLogin.lastOutcome(this)) {
        "needSecondFactor" -> getString(R.string.auto_renew_last_need_sms)
        "rejected" -> getString(R.string.auto_renew_last_rejected)
        else -> null
    }

    override fun onDestroy() {
        // 离开设置页即暂停下载(保留 .part,可断点续传),
        // 同时防止销毁后残留的下载协程与新页面的下载并发写同一文件
        if (::downloader.isInitialized) downloader.cancel()
        super.onDestroy()
    }

    private fun checkForUpdate() {
        uiState = UpdateUiState.CHECKING
        renderUpdateUi()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = UpdateChecker().check(currentVersion)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is UpdateCheckResult.UpToDate -> {
                            latestRelease = null
                            ApkUpdateDownloader.cleanupExcept(this@SettingsActivity, null)
                            uiState = UpdateUiState.UP_TO_DATE
                        }
                        is UpdateCheckResult.Available -> {
                            latestRelease = result.release
                            ApkUpdateDownloader.cleanupExcept(this@SettingsActivity, result.release.versionName)
                            uiState = if (findReadyApk(result.release) != null) {
                                UpdateUiState.DOWNLOADED
                            } else {
                                UpdateUiState.AVAILABLE
                            }
                        }
                    }
                    renderUpdateUi()
                }
            } catch (e: Exception) {
                lastMessage = e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main) {
                    uiState = UpdateUiState.CHECK_FAILED
                    renderUpdateUi()
                }
            }
        }
    }

    private fun startDownload() {
        val release = latestRelease ?: return
        speedEma = 0.0
        lastProgressBytes = 0L
        lastProgressTime = 0L
        uiState = UpdateUiState.DOWNLOADING
        renderUpdateUi()
        lifecycleScope.launch(Dispatchers.IO) {
            downloader.download(release, downloadListener)
        }
    }

    private fun pauseDownload() {
        downloader.cancel()
        uiState = UpdateUiState.PAUSED
        renderUpdateUi()
    }

    private fun installApk() {
        val release = latestRelease ?: return
        val apk = findReadyApk(release) ?: run {
            // 安装包意外丢失,退回可下载状态
            uiState = UpdateUiState.AVAILABLE
            renderUpdateUi()
            return
        }
        ApkInstaller.install(this, apk)
    }

    /** 已下载完成且大小与 release 一致的 APK(大小不符则当作坏文件删掉) */
    private fun findReadyApk(release: ReleaseInfo): File? {
        val apk = ApkUpdateDownloader.downloadedApk(this, release.versionName)
        if (!apk.exists()) return null
        if (release.apkSize > 0 && apk.length() != release.apkSize) {
            apk.delete()
            return null
        }
        return apk
    }

    private fun renderUpdateUi() {
        val release = latestRelease
        btnCheckUpdate.isEnabled = uiState != UpdateUiState.CHECKING &&
            uiState != UpdateUiState.DOWNLOADING

        fun showStatus(text: String) {
            tvUpdateStatus.visibility = TextView.VISIBLE
            tvUpdateStatus.text = text
        }
        fun hideStatus() {
            tvUpdateStatus.visibility = TextView.GONE
        }

        when (uiState) {
            UpdateUiState.IDLE -> {
                hideStatus()
                tvChangelog.visibility = TextView.GONE
                progressDownload.visibility = ProgressBar.GONE
                tvProgress.visibility = TextView.GONE
                btnUpdateAction.visibility = MaterialButton.GONE
            }
            UpdateUiState.CHECKING -> {
                showStatus(getString(R.string.update_checking))
                tvChangelog.visibility = TextView.GONE
                progressDownload.visibility = ProgressBar.GONE
                tvProgress.visibility = TextView.GONE
                btnUpdateAction.visibility = MaterialButton.GONE
            }
            UpdateUiState.CHECK_FAILED -> {
                showStatus(getString(R.string.update_check_failed, lastMessage))
                tvChangelog.visibility = TextView.GONE
                progressDownload.visibility = ProgressBar.GONE
                tvProgress.visibility = TextView.GONE
                btnUpdateAction.visibility = MaterialButton.GONE
            }
            UpdateUiState.UP_TO_DATE -> {
                showStatus(getString(R.string.update_up_to_date, currentVersion))
                tvChangelog.visibility = TextView.GONE
                progressDownload.visibility = ProgressBar.GONE
                tvProgress.visibility = TextView.GONE
                btnUpdateAction.visibility = MaterialButton.GONE
            }
            UpdateUiState.AVAILABLE -> {
                showStatus(release.availableText())
                tvChangelog.visibility = if (release?.notes != null) TextView.VISIBLE else TextView.GONE
                tvChangelog.text = release?.notes
                progressDownload.visibility = ProgressBar.GONE
                tvProgress.visibility = TextView.GONE
                btnUpdateAction.visibility = MaterialButton.VISIBLE
                val hasPartial = release != null &&
                    ApkUpdateDownloader.partialFile(this, release.versionName).exists()
                btnUpdateAction.text = getString(
                    if (hasPartial) R.string.update_resume else R.string.update_download
                )
            }
            UpdateUiState.DOWNLOADING -> {
                showStatus(release.availableText())
                tvChangelog.visibility = if (release?.notes != null) TextView.VISIBLE else TextView.GONE
                tvChangelog.text = release?.notes
                progressDownload.visibility = ProgressBar.VISIBLE
                progressDownload.isIndeterminate = true
                tvProgress.visibility = TextView.VISIBLE
                tvProgress.text = formatBytes(0)
                btnUpdateAction.visibility = MaterialButton.VISIBLE
                btnUpdateAction.text = getString(R.string.update_pause)
            }
            UpdateUiState.PAUSED -> {
                showStatus(
                    getString(
                        R.string.update_paused,
                        formatBytes(release?.let { partialProgressBytes(it) } ?: 0L),
                    )
                )
                tvChangelog.visibility = TextView.GONE
                renderStaticProgress()
                btnUpdateAction.visibility = MaterialButton.VISIBLE
                btnUpdateAction.text = getString(R.string.update_resume)
            }
            UpdateUiState.FAILED -> {
                showStatus(getString(R.string.update_download_failed, lastMessage))
                tvChangelog.visibility = TextView.GONE
                renderStaticProgress()
                btnUpdateAction.visibility = MaterialButton.VISIBLE
                btnUpdateAction.text = getString(R.string.update_resume)
            }
            UpdateUiState.DOWNLOADED -> {
                showStatus(
                    getString(R.string.update_downloaded, release?.versionName ?: currentVersion)
                )
                tvChangelog.visibility = TextView.GONE
                progressDownload.visibility = ProgressBar.GONE
                tvProgress.visibility = TextView.GONE
                btnUpdateAction.visibility = MaterialButton.VISIBLE
                btnUpdateAction.text = getString(R.string.update_install)
            }
        }
    }

    /** 暂停/失败态:按 .part 大小恢复进度条显示 */
    private fun renderStaticProgress() {
        val release = latestRelease
        val bytes = release?.let { partialProgressBytes(it) } ?: 0L
        val size = release?.apkSize ?: 0L
        progressDownload.visibility = ProgressBar.VISIBLE
        tvProgress.visibility = TextView.VISIBLE
        if (size > 0) {
            progressDownload.isIndeterminate = false
            progressDownload.progress = ((bytes * 100) / size).toInt().coerceIn(0, 100)
            tvProgress.text = "${formatBytes(bytes)} / ${formatBytes(size)} · ${(bytes * 100 / size).coerceAtMost(100)}%"
        } else {
            tvProgress.text = formatBytes(bytes)
        }
    }

    private fun partialProgressBytes(release: ReleaseInfo): Long =
        ApkUpdateDownloader.partialFile(this, release.versionName)
            .takeIf { it.exists() }?.length() ?: 0L

    private fun ReleaseInfo?.availableText(): String {
        if (this == null) return ""
        return if (apkSize > 0) {
            getString(R.string.update_available, versionName, formatBytes(apkSize))
        } else {
            getString(R.string.update_available, versionName, "未知大小")
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
