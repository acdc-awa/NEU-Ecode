package com.neboer.ecode

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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

        val settings = AppSettings(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

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

        findViewById<MaterialButton>(R.id.btnSwitchAccount).setOnClickListener {
            PersistentCookieJar(this).clear()
            CredentialManager(this).clear()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
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

        // 下载源下拉:选项与选中值均来自 UpdateChecker.UI_SOURCES
        val ddlUpdateSource: MaterialAutoCompleteTextView = findViewById(R.id.ddlUpdateSource)
        val sourceLabels = UpdateChecker.UI_SOURCES.map { it.label }.toTypedArray()
        ddlUpdateSource.setSimpleItems(sourceLabels)
        val savedSource = UpdateChecker.sourceById(settings.updateSourceId)
        ddlUpdateSource.setText(savedSource.label, false)
        ddlUpdateSource.setOnItemClickListener { _, _, position, _ ->
            val picked = UpdateChecker.UI_SOURCES[position]
            settings.updateSourceId = picked.id
            Log.i(TAG, "下载源切换为: ${picked.label}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"授权页回来后续上安装
        ApkInstaller.resumePendingInstall(this)
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
        // 自动模式传 null(API 查询直连优先走代理兜底),手动源则该源优先
        val preferredPrefix = UpdateChecker.sourceById(AppSettings(this).updateSourceId).prefix
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = UpdateChecker().check(currentVersion, preferredPrefix)
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
        val sourceId = AppSettings(this).updateSourceId
        lifecycleScope.launch(Dispatchers.IO) {
            downloader.download(release, sourceId, downloadListener)
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
