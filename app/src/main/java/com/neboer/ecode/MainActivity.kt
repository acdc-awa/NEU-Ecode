package com.neboer.ecode

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        /** qrInvalidTime 到期后延迟该毫秒数再拉新码 */
        private const val QR_EXPIRE_BUFFER_MS = 500L

        /** 服务端未给 qrInvalidTime 时的兜底轮询间隔 */
        private const val QR_FALLBACK_INTERVAL_MS = 10_000L

        /** 网络异常重试:首次 5s,翻倍,上限 30s */
        private const val NET_RETRY_BASE_MS = 5_000L
        private const val NET_RETRY_MAX_MS = 30_000L

        /**
         * 门户会话保活间隔。CASTGC 只有 2 小时且无法续期,门户余额靠的 CK_LC/CK_VL 只能
         * 由"兑一次票"重新签发,所以前台定期打一发:凭据还活着就把 SESS_ID 窗口往后推,
         * 已经死了就在 CASTGC 还在的 2 小时窗口内静默重建。
         */
        private const val PORTAL_KEEPALIVE_INTERVAL_MS = 30 * 60 * 1000L

        /** 另有一发静默重登在跑时,等它收场再重试拉码的间隔(见 AuthExpired 分支) */
        private const val SILENT_RELOGIN_WAIT_MS = 3_000L
    }

    private lateinit var apiClient: EcodeApiClient
    private lateinit var portalClient: PortalClient
    private lateinit var settings: AppSettings

    private lateinit var tvUsername: TextView
    private lateinit var cardStatusPill: MaterialCardView
    private lateinit var tvStatus: TextView
    private lateinit var cardBalance: MaterialCardView
    private lateinit var tvBalance: TextView
    private lateinit var btnRefreshBalance: ImageButton
    private lateinit var ivQRCode: ImageView
    private lateinit var layoutQRPlaceholder: View
    private lateinit var tvPlaceholderText: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var cardQRCode: MaterialCardView
    private lateinit var btnHelp: ImageButton
    private lateinit var btnSettings: ImageButton

    private var refreshJob: Job? = null
    private var balanceJob: Job? = null
    private var keepAliveJob: Job? = null
    private var balanceSpin: ObjectAnimator? = null
    private var qrBitmap: Bitmap? = null
    private var currentQrCode: String? = null
    private var lastAppliedHdr: Boolean = false
    private var qrVisible: Boolean = true
    private var originalBrightness: Float = -1f
    private var lastBackPressTime: Long = 0

    /** UI 层登录态:决定主界面显示数据还是空态(会话唯一来源仍是 CookieManager) */
    private var loggedIn: Boolean = false

    /** 本次会话是否已自动拉过一次余额(登录成功回主界面刷一次,之后仅手动) */
    private var balanceAutoLoaded: Boolean = false

    private lateinit var credentialStore: CredentialStore

    /**
     * 上一次静默重登"成功"之后二维码又立刻报会话失效。
     *
     * 现在的"成功"已经带真校验(换到票 + `qr-code` 200)了,所以这种情况理论上不该出现;
     * 留着它是因为"服务端行为会变"是常态,而一旦出现,再静默重试也救不回来 ——
     * 只给一次机会,第二次直接降级成空态,把决定权交回用户,避免变成看不见的死循环。
     * 拉码成功即清掉,所以它只描述"这一轮失效"。
     *
     * 它在 AuthExpired 分支里与 `SilentLogin.decide()` **并列**判断,不能只放在 decide() 的
     * 非 ALLOW 分支里:成功后写下的 60s 冷却一过,ALLOW 就会回来,那样这道门等于失效。
     */
    private var silentReloginUnhelpful = false

    /** 正在跑的静默重登。同时只允许一发:两个触发点可能同时判定会话失效 */
    private var silentLoginJob: Job? = null

    /**
     * 静默重登的入口:一个普通协程,不经过任何界面([SilentRelogin] 直接走 CAS 协议)。
     *
     * 三个结局各自的收场是"登录与自动续期融为一体"的关键:
     * - [SilentLoginOutcome.Success] 补做原先失败的那件事(继续拉码 / 重查余额)
     * - [SilentLoginOutcome.NeedSecondFactor] 把用户送到登录页的二次验证面板接着做
     * - 其余(网络异常、没存账密、冷却、熔断)干净降级,由用户自己决定要不要手动登录
     *
     * 返回 true = 这一发归本方法收场;返回 false = 已有一发在跑,本次既没登录也没回调。
     * 调用方必须区分这两件事:**返回 false 时不会有人替它收场**,它得自己决定等还是降级
     * (二维码那条路就靠这个返回值避免把刷新循环停在一发它并不拥有的重登上)。
     */
    private fun runSilentRelogin(onSuccess: () -> Unit, onFailure: () -> Unit): Boolean {
        if (silentLoginJob?.isActive == true) {
            Log.i(TAG, "已有一发静默重登在跑,本次跳过")
            return false
        }
        silentLoginJob = lifecycleScope.launch {
            val outcome = SilentRelogin.run(this@MainActivity, credentialStore)
            Log.i(TAG, "静默重登结局: ${SilentLogin.nameOf(outcome)}")
            when (outcome) {
                SilentLoginOutcome.Success -> {
                    // 直接置为已登录:这一发的"成功"是 [SilentRelogin] 里那次真校验
                    // (qr-code 200)证明的,比"本地 cookie 里有 SESSION"更强。两者不一致只
                    // 可能是会话在验完之后又掉了,记一行日志留证,但不因此把界面打回空态
                    if (!WebViewCookieJar.hasEcodeSession()) {
                        Log.w(TAG, "静默重登已通过真校验,但本地读不到 SESSION cookie")
                    }
                    loggedIn = true
                    renderSessionState()
                    onSuccess()
                }

                // 账密是对的,只差一个验证码。这一刻用户就在屏幕前,而应用自己不能也不会
                // 替他发短信 —— 所以把这一发直接交给登录页的二次验证面板收尾,表单沿用
                // 刚才那张(服务端已经接受了这次账密),用户只需填一次验证码
                is SilentLoginOutcome.NeedSecondFactor -> {
                    Log.i(TAG, "静默续期需要二次验证,转交登录页继续(复用已通过账密的二验表单)")
                    onFailure()
                    openLoginForSecondFactor(outcome.form)
                }

                SilentLoginOutcome.Rejected -> {
                    // 存的那份账密已被服务端否定。让用户去登录页重来一次,并且**不要把那个
                    // 已知错误的密码再预填回去**;连续两次被拒会熔断,那时保存的密码也会被清掉
                    Log.w(TAG, "保存的账号密码被拒,转交登录页重新登录")
                    onFailure()
                    openLoginForPasswordInvalid()
                }

                is SilentLoginOutcome.Error -> {
                    Toast.makeText(this@MainActivity, R.string.silent_login_failed, Toast.LENGTH_LONG).show()
                    onFailure()
                }

                // 没执行:账本里已记了原因(没存账密/冷却/熔断),安静降级即可 ——
                // 退出到空态本身就是"需要你手动登录一次"的信号
                SilentLoginOutcome.Unavailable -> {
                    Log.i(
                        TAG,
                        "静默重登未执行(${SilentLogin.describe(this@MainActivity, credentialStore)}),退回空态",
                    )
                    onFailure()
                }
            }
        }
        return true
    }

    /** 打开登录页的二次验证面板,带上刚才那张已被服务端接受的表单 */
    private fun openLoginForSecondFactor(form: CasSecondFactorForm) {
        if (isFinishing || isDestroyed) return
        Log.i(TAG, "转到登录页二次验证面板")
        startActivity(LoginActivity.intentForSecondFactor(this, form))
    }

    /** 打开登录页,提示保存的密码已失效(账号预填、密码清空) */
    private fun openLoginForPasswordInvalid() {
        if (isFinishing || isDestroyed) return
        Log.i(TAG, "转到登录页,保存的密码已失效")
        startActivity(LoginActivity.intentForInvalidPassword(this))
    }

    /**
     * 二维码会话确实死了(CASTGC 失效、静默重登也没救回来):退回空态让用户手动登录。
     *
     * 只清 ecode 域自己的 cookie,保留 CASTGC 与门户凭据(CK_LC/CK_VL)——一次"二维码会话
     * 过期"不该把还能用的余额一起废掉,逼出一次本可避免的完整重新登录。
     */
    private fun degradeQrSession() {
        Log.w(TAG, "二维码会话已失效,只清 ecode 域 cookie,保留 CASTGC 与门户凭据")
        WebViewCookieJar.clearEcodeSession()
        loggedIn = false
        // 这一轮已经认输了,把"静默没救活"的记账清掉:用户手动登录回来后是全新的一轮
        silentReloginUnhelpful = false
        renderSessionState()
        tvStatus.text = getString(R.string.login_expired)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_main)

        tvUsername = findViewById(R.id.tvUsername)
        cardStatusPill = findViewById(R.id.cardStatusPill)
        tvStatus = findViewById(R.id.tvStatus)
        cardBalance = findViewById(R.id.cardBalance)
        tvBalance = findViewById(R.id.tvBalance)
        btnRefreshBalance = findViewById(R.id.btnRefreshBalance)
        ivQRCode = findViewById(R.id.ivQRCode)
        layoutQRPlaceholder = findViewById(R.id.layoutQRPlaceholder)
        tvPlaceholderText = findViewById(R.id.tvPlaceholderText)
        btnLogin = findViewById(R.id.btnLogin)
        cardQRCode = findViewById(R.id.cardQRCode)
        btnHelp = findViewById(R.id.btnHelp)
        btnSettings = findViewById(R.id.btnSettings)

        settings = AppSettings(this)
        credentialStore = CredentialStore(this)
        // 统一客户端标识:静默重登现在由原生 OkHttp 直接发 CAS 请求,如果等到用户手动打开
        // 登录页才对齐 UA,那之前发出的请求都会顶着兜底 UA(与 WebView 不一致)。
        // 这一步只是问 WebView 提供方要一个默认 UA,并不会创建 WebView。
        UserAgent.adoptWebViewUserAgent(this)

        originalBrightness = readSystemBrightness()

        // 边到边沉浸式适配:为顶部 Header 注入状态栏 Padding,为根视图注入手势导航栏 Padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            findViewById<View>(R.id.layoutHeader).updatePadding(top = statusBarInsets.top)
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        // 会话唯一来源 = CookieManager(WebView 登录种下),OkHttp 经 WebViewCookieJar 共享。
        // 2026-09-14 起是全局单例:静默重登现在由原生 OkHttp 直接发 CAS 请求,主界面/登录页/
        // 设置页都必须落在同一份 cookie 上,否则"静默换到的票"和"界面看到的会话"会是两回事
        val okHttpClient = AppHttp.client

        apiClient = EcodeApiClient(okHttpClient)
        portalClient = PortalClient(okHttpClient)

        tvUsername.text = getString(R.string.app_name)

        btnRefreshBalance.setOnClickListener { loadBalance() }
        cardBalance.setOnClickListener { loadBalance() }
        btnLogin.setOnClickListener { openLogin() }
        btnHelp.setOnClickListener { showHelpDialog() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        cardQRCode.setOnClickListener {
            // 未登录空态下点卡片无意义,不进入隐藏/显示切换
            if (!loggedIn) return@setOnClickListener
            qrVisible = !qrVisible
            // 仅会话内切换,不持久化:下次开屏仍按设置的"开屏二维码显示"偏好
            applyQRVisibility()
            // 重新显示时立即重启刷新拉新码(隐藏时 applyQRVisibility 已停掉循环)
            if (qrVisible) startQRRefresh()
        }

        qrVisible = settings.qrShowOnLaunch
        loggedIn = WebViewCookieJar.hasEcodeSession()
        renderSessionState()
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_main_title)
            .setMessage(R.string.help_main_message)
            .setPositiveButton(R.string.help_action_got_it, null)
            .show()
    }

    /** 按登录态渲染整个主界面:已登录 = 二维码/余额数据态;未登录 = 空态 + 去登录按钮 */
    private fun renderSessionState() {
        if (loggedIn) {
            cardStatusPill.setCardBackgroundColor(getColor(R.color.status_pill_background))
            tvStatus.setTextColor(getColor(R.color.status_pill_text))
            tvStatus.text = getString(R.string.fetching_qr)
            tvBalance.text = getString(R.string.balance_unknown)
            btnRefreshBalance.isEnabled = true
            cardBalance.isEnabled = true
            applyQRVisibility()
        } else {
            refreshJob?.cancel()
            refreshJob = null
            balanceJob?.cancel()
            balanceJob = null
            keepAliveJob?.cancel()
            keepAliveJob = null
            balanceAutoLoaded = false
            setBalanceRefreshing(false)
            cardStatusPill.setCardBackgroundColor(getColor(R.color.md_theme_surface_container_high))
            tvStatus.setTextColor(getColor(R.color.md_theme_on_surface_variant))
            tvStatus.text = getString(R.string.status_not_logged_in)
            tvBalance.text = getString(R.string.balance_unknown)
            btnRefreshBalance.isEnabled = false
            cardBalance.isEnabled = false
            qrBitmap = null
            currentQrCode = null
            ivQRCode.setImageBitmap(null)
            ivQRCode.visibility = View.GONE
            layoutQRPlaceholder.visibility = View.VISIBLE
            tvPlaceholderText.setText(R.string.not_logged_in_placeholder)
            btnLogin.visibility = View.VISIBLE
            cardQRCode.setCardBackgroundColor(getColor(R.color.md_theme_surface_container))
            applyBrightness(false)
        }
    }

    /** 空态"去登录"按钮:原生登录表单;成功后登录页 CLEAR_TOP 回到这里 */
    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        originalBrightness = readSystemBrightness()

        // 从登录页/设置页回来:登录态可能已变化(登录成功种下 SESSION 或被切换账号清空)。
        // 但静默重登途中不重判:那一刻本地 cookie 本来就不代表最终状态,重判会把界面翻成
        // 未登录,并连带取消正在救场的刷新循环
        if (silentLoginJob?.isActive != true) {
            val hasSession = WebViewCookieJar.hasEcodeSession()
            if (hasSession != loggedIn) {
                Log.d(TAG, "登录态变化: $loggedIn -> $hasSession")
                // 重新登录回来了:余额该跟着刷一次。不重置的话这个标记还停在上一轮登录的 true,
                // 于是刚登录完余额显示"未知"、非得手点一下才出来
                if (hasSession) balanceAutoLoaded = false
                loggedIn = hasSession
                renderSessionState()
            }
        }

        // 高亮模式可能在设置页被修改:如果 HDR 激活状态发生变化且已有二维码，重新生成位图以挂载/移除 Gainmap
        val currentHdr = isHdrHighlightActive()
        val savedCode = currentQrCode
        if (loggedIn && qrVisible && savedCode != null && currentHdr != lastAppliedHdr) {
            lastAppliedHdr = currentHdr
            lifecycleScope.launch(Dispatchers.Default) {
                val bitmap = generateQRBitmap(savedCode, 560)
                withContext(Dispatchers.Main) {
                    qrBitmap = bitmap
                    ivQRCode.setImageBitmap(bitmap)
                }
            }
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

        // 门户会话保活:每次回前台打一发,并在此期间每 30 分钟续一次窗口,
        // 避免 CASTGC 2 小时上限一到余额就再也建不起会话
        if (loggedIn && keepAliveJob?.isActive != true) {
            startPortalKeepAlive()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退后台/回桌面即停止二维码刷新(程序留在后台也不发请求)
        refreshJob?.cancel()
        refreshJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        applyBrightness(false)
    }

    /** 门户会话保活循环:先打一发再等间隔,所以每次回前台都会立即续一次窗口 */
    private fun startPortalKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = lifecycleScope.launch {
            while (isActive) {
                // castgc 在保活前读:日志时间线能直接看出它是不是登录满 2 小时后消失
                val castgc = WebViewCookieJar.hasCastgc()
                val ok = withContext(Dispatchers.IO) { portalClient.keepSessionAlive() }
                Log.d(TAG, "门户会话保活: ok=$ok, castgc=$castgc")
                delay(PORTAL_KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    /**
     * 前台 + 二维码可见期间的刷新循环:
     * 用户要求:状态直接显示“二维码有效”,不再显示过期时钟;后台仍依据 qrInvalidTime 定下次拉取时刻
     */
    private fun startQRRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var netRetryMs = NET_RETRY_BASE_MS
            while (isActive) {
                val result = withContext(Dispatchers.IO) { apiClient.fetchQRCode() }

                when (result) {
                    is QrFetchResult.Success -> {
                        netRetryMs = NET_RETRY_BASE_MS
                        // 真拉到码了才说明会话是活的:清掉"静默重登没救活"的记账
                        silentReloginUnhelpful = false
                        currentQrCode = result.qrCode
                        lastAppliedHdr = isHdrHighlightActive()
                        val bitmap = withContext(Dispatchers.Default) {
                            generateQRBitmap(result.qrCode, 560)
                        }
                        qrBitmap = bitmap

                        // 平滑 Crossfade 更新二维码，消除生硬闪图
                        ivQRCode.alpha = 0.6f
                        ivQRCode.setImageBitmap(bitmap)
                        ivQRCode.animate().alpha(1.0f).setDuration(200L).start()

                        // 直接显示“二维码有效”，简洁纯粹
                        cardStatusPill.setCardBackgroundColor(getColor(R.color.status_pill_background))
                        tvStatus.setTextColor(getColor(R.color.status_pill_text))
                        tvStatus.text = getString(R.string.qr_valid_no_expiry)

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
                        val decision = SilentLogin.decide(this@MainActivity, credentialStore)
                        // 能自己发起一发,就交给它收场(它的成功回调会重新拉起本循环),本轮到此结束。
                        //
                        // silentReloginUnhelpful 是 decide() 之外单独的一道门:它记的是"上一发自报
                        // 成功却没能救活二维码"。不能指望 decide() 拦住第二次 —— record(Success)
                        // 只写 60s 冷却,冷却一过 ALLOW 又会回来,于是"成功但没用"会被无限重复。
                        val started = decision == SilentLogin.Decision.ALLOW &&
                            !silentReloginUnhelpful &&
                            runSilentRelogin(
                                onSuccess = {
                                    silentReloginUnhelpful = true
                                    startQRRefresh()
                                },
                                onFailure = { degradeQrSession() },
                            )
                        if (started) {
                            Log.w(TAG, "ecode 会话已失效,已发起静默重登,本轮循环结束由它收场")
                            tvStatus.text = getString(R.string.silent_login_running)
                            return@launch
                        }

                        // 没发起(或没发起来)时,只要还有一发在跑就**不能结束循环**:那一发可能由
                        // 余额那条路发起,它的成功回调只会重查余额,不会拉起本循环 —— 停在原地等,
                        // 会话被它救活就自然接上;没救活则下一轮 decide() 已是冷却/熔断,落到下面的降级。
                        // 等待是有界的:静默重登自身有 15–20s 的 HTTP 超时,且结局必然记账。
                        val anotherInFlight = decision == SilentLogin.Decision.IN_FLIGHT ||
                            (decision == SilentLogin.Decision.ALLOW && !silentReloginUnhelpful)
                        if (anotherInFlight) {
                            Log.i(TAG, "已有静默重登在跑,等待其结束后重试拉码")
                            tvStatus.text = getString(R.string.silent_login_running)
                            delay(SILENT_RELOGIN_WAIT_MS)
                            continue
                        }

                        if (silentReloginUnhelpful) {
                            Log.w(TAG, "上次静默重登自报成功但二维码依旧失效,不再重试,退回空态")
                        } else {
                            // 没存账密/冷却中/已熔断:日志里记下是哪一道门挡的
                            Log.i(
                                TAG,
                                "静默重登不可用(${SilentLogin.describe(this@MainActivity, credentialStore)}),直接降级",
                            )
                        }
                        degradeQrSession()
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

    /** 启动时和点按钮时拉一次余额(门户个人数据 JSON 接口);进行中重复点击忽略 */
    private fun loadBalance() {
        if (!loggedIn) return
        if (balanceJob?.isActive == true) return
        balanceJob = lifecycleScope.launch {
            setBalanceRefreshing(true)
            tvBalance.text = getString(R.string.balance_loading)
            val result = withContext(Dispatchers.IO) {
                portalClient.fetchBalance()
            }
            setBalanceRefreshing(false)
            when (result) {
                is BalanceResult.Success ->
                    tvBalance.text = getString(R.string.balance_format, result.value)

                BalanceResult.NetworkUnreachable -> {
                    Log.w(TAG, "余额网络不可达")
                    Toast.makeText(this@MainActivity, R.string.balance_network_unreachable, Toast.LENGTH_LONG)
                        .show()
                    tvBalance.text = getString(R.string.balance_unknown)
                }

                BalanceResult.SessionExpired -> {
                    // CK_LC/CK_VL 只能靠兑票重新签发,而兑票需要活的 CASTGC —— 这正是
                    // "余额在一段时间后必然失效"的根因,所以这里先试一次静默重登
                    Log.w(TAG, "余额会话已过期(CASTGC失效),尝试静默重登")
                    tvBalance.text = getString(R.string.balance_loading)
                    val started = runSilentRelogin(
                        // 换到新 TGT 后余额流程会自己经 cas_login 兑票重建门户会话
                        onSuccess = { loadBalance() },
                        onFailure = {
                            Toast.makeText(this@MainActivity, R.string.balance_session_expired, Toast.LENGTH_LONG)
                                .show()
                            tvBalance.text = getString(R.string.balance_unknown)
                        },
                    )
                    if (!started) {
                        // 已有一发在跑:它的成功回调是二维码那边的,不一定重查余额。不能停在
                        // "加载中"等一个不会来的回调 —— 静默重登结束后用户点一下刷新即可
                        Log.i(TAG, "已有静默重登在跑,本次余额不再等待")
                        tvBalance.text = getString(R.string.balance_unknown)
                    }
                }

                BalanceResult.Failed -> {
                    Log.w(TAG, "余额获取失败")
                    Toast.makeText(this@MainActivity, R.string.balance_refresh_failed, Toast.LENGTH_SHORT)
                        .show()
                    tvBalance.text = getString(R.string.balance_unknown)
                }
            }
        }
    }

    private fun setBalanceRefreshing(refreshing: Boolean) {
        btnRefreshBalance.isEnabled = !refreshing && loggedIn
        cardBalance.isEnabled = !refreshing && loggedIn
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

    private fun isHdrHighlightActive(): Boolean {
        return settings.qrBrightnessMode == QrBrightnessMode.HDR && HdrHelper.isHdrSupported(this)
    }

    private fun generateQRBitmap(content: String, size: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                // HDR Gainmap 需基图有非零白底(Color.WHITE)才能倍增高光;
                // 若为 TRANSPARENT, Gain 乘算仍为 0
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        if (isHdrHighlightActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HdrHelper.attachGainmap(bitmap, bitMatrix, size)
        }
        return bitmap
    }

    /** 已登录时二维码显示/隐藏(隐藏 = 停刷新;显示 = 由调用方重启刷新拉新码) */
    private fun applyQRVisibility() {
        if (qrVisible) {
            ivQRCode.visibility = View.VISIBLE
            layoutQRPlaceholder.visibility = View.GONE
            btnLogin.visibility = View.GONE
            cardQRCode.setCardBackgroundColor(getColor(R.color.qr_canvas_background))
            qrBitmap?.let { ivQRCode.setImageBitmap(it) }
            applyBrightness(true)
        } else {
            ivQRCode.visibility = View.GONE
            layoutQRPlaceholder.visibility = View.VISIBLE
            btnLogin.visibility = View.GONE
            cardQRCode.setCardBackgroundColor(getColor(R.color.md_theme_surface_container))
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
            val activeHdr = isHdrHighlightActive()
            if (activeHdr) {
                // HDR 局部高亮: 窗口切至 HDR 颜色模式，屏幕背光维持系统当前亮度，其余组件不刺眼
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    window.colorMode = ActivityInfo.COLOR_MODE_HDR
                }
                window.attributes = window.attributes.apply {
                    screenBrightness = originalBrightness
                }
            } else {
                // 传统全屏高亮或跟随系统亮度
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                }
                val targetBrightness = if (settings.qrBrightnessMode == QrBrightnessMode.SYSTEM) {
                    originalBrightness
                } else {
                    1.0f
                }
                window.attributes = window.attributes.apply {
                    screenBrightness = targetBrightness
                }
            }
        } else {
            // 退出前台、隐藏二维码或未登录时，还原 SDR 默认颜色模式与原本系统亮度
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
            window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness
            }
        }
    }

    @Suppress("MissingSuperCall")
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
        keepAliveJob?.cancel()
        balanceSpin?.cancel()
    }
}
