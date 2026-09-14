package com.neboer.ecode

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 交互式登录页:应用自有表单,不再套 WebView。
 *
 * 与静默续期共用 [CasAuthClient],区别只是"旁边有人在填验证码"——这正是"登录 + 自动续期
 * 一体化"的落点:两条路走同一段协议代码,于是不存在"静默那条能过、手动这条过不了"的分裂。
 *
 * 三个面板(同一 Activity 内切换,不新开页面):
 * 1. 账密表单 —— `fetchLoginForm()` 取 `lt`/`execution`/表单地址,`submitCredentials()` 提交
 * 2. 二次验证 —— 图形码(`fetchCaptcha`)+ 短信码:点"获取短信验证码"才调 `requestSmsCode()`
 *    (**整个应用里唯一会让短信真的发出去的调用**,所以它只挂在这个按钮上),
 *    再 `submitSecondFactor()` 提交
 * 3. 加载失败 —— 说明原因,给"重试"和"网页登录"
 *
 * 它同时是**唯一的凭据录入处**:勾选框"保存账号密码"就是自动续期的开关,设置页不再单独
 * 提供录入对话框。勾选/取消勾选都只在**登录成功之后**才生效(见 [applyCredentialChoice]),
 * 所以取消勾选不会因为一次失败的尝试而把已存的凭据删掉。
 *
 * 三种进入方式:
 * - 空态"去登录"、设置页"登录/切换账号" —— 正常从头走
 * - 静默续期撞上二次验证([EXTRA_SECOND_FACTOR])—— 直接落到二验面板,复用那张已经被
 *   服务端接受的表单,不必把同样的账密再提交一遍
 * - 静默续期的账号密码被拒([EXTRA_INVALID_PASSWORD])—— 账号预填、密码清空,并说明原因
 *
 * 成功判据与静默路径完全一致:`CasStep.Ticket` 的 Location 交给 [CasAuthClient.consumeTicket]
 * 消费掉,再用 `EcodeApiClient.hasAuthenticatedSession()` 做真校验(qr-code 200)。**不看本地
 * cookie 在不在**——上一版无限闪屏就是拿"cookie 里有 SESSION"当成功的下场(那是约 100 天的
 * 持久 cookie,在不在与服务端认不认它是两件事)。
 *
 * `lt`/`execution` 是一次性的:账密被拒之后不能拿旧表单再提交,必须重新取一张
 * ([loadLoginForm]),所以 [CasStep.BadCredentials] 的处理是"重新取表单 + 把那句话显示出来"。
 *
 * 页面底部常驻一个"网页登录"入口([WebLoginActivity],真 WebView):整个应用是屏幕抓取,
 * 校方改版可能让原生解析整个失灵,而"登录"是唯一不能失灵的功能。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"

        /** 二验方式:只有短信与邮箱能用"图形码 + 验证码"这套原生表单走完 */
        private const val MODE_MOBILE = "mobile"
        private const val MODE_EMAIL = "email"

        /** 静默续期交过来的二验表单:账密已被服务端接受,用户只差一个验证码 */
        private const val EXTRA_SECOND_FACTOR = "second_factor_form"

        /** 保存的那份密码已被服务端否定:账号预填、密码清空 */
        private const val EXTRA_INVALID_PASSWORD = "invalid_password"

        /** 静默续期撞上二次验证时,由主界面调用:直接落到二验面板继续 */
        fun intentForSecondFactor(context: android.content.Context, form: CasSecondFactorForm): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SECOND_FACTOR, form)

        /** 保存的账密被拒时,由主界面调用 */
        fun intentForInvalidPassword(context: android.content.Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_INVALID_PASSWORD, true)
    }

    private val cas = CasAuthClient(AppHttp.client)

    /** 当前这张一次性表单;账密被拒后会整体换新 */
    private var form: CasLoginForm? = null
    private var secondFactor: CasSecondFactorForm? = null

    /** 有请求在飞:挡住重复点击与并发提交(lt 是一次性的,叠着提交必然失败) */
    private var busy = false

    /**
     * 本次登录用的账密(提交后暂存)。保存/清除要等**登录成功**之后再做:失败时不该动
     * 已经存好的凭据,更不该把一份打错的密码存进去。
     */
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null

    /** 进页面时就该显示的错误(如"保存的密码已失效"),在表单取回来后展示 */
    private var pendingInitialError: String? = null

    private lateinit var groupCredentials: View
    private lateinit var groupSecondFactor: View
    private lateinit var groupLoadError: View
    private lateinit var progressLogin: LinearProgressIndicator
    private lateinit var tvLoginProgress: TextView
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var cbSaveCredentials: CheckBox
    private lateinit var tvError: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var ivCaptcha: ImageView
    private lateinit var btnRefreshCaptcha: MaterialButton
    private lateinit var etCaptcha: TextInputEditText
    private lateinit var tilSmsCode: TextInputLayout
    private lateinit var etSmsCode: TextInputEditText
    private lateinit var tv2faError: TextView
    private lateinit var btnSendSms: MaterialButton
    private lateinit var btnSubmit2fa: MaterialButton
    private lateinit var tvLoadError: TextView
    private lateinit var cardLoginStatus: View

    /** 这两颗按钮都只是"重取一张表单",所以也归 [setBusy] 管:busy 时它们点了也不会有反应 */
    private lateinit var btnRestartLogin: MaterialButton
    private lateinit var btnRetry: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate(原生登录表单)")
        setContentView(R.layout.activity_login)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLoginLayout)) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            findViewById<View>(R.id.toolbarLogin).updatePadding(top = statusBarInsets.top)
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        progressLogin = findViewById(R.id.progressLogin)
        tvLoginProgress = findViewById(R.id.tvLoginProgress)
        groupCredentials = findViewById(R.id.groupCredentials)
        groupSecondFactor = findViewById(R.id.groupSecondFactor)
        groupLoadError = findViewById(R.id.groupLoadError)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        cbSaveCredentials = findViewById(R.id.cbSaveCredentials)
        tvError = findViewById(R.id.tvError)
        btnLogin = findViewById(R.id.btnLogin)
        ivCaptcha = findViewById(R.id.ivCaptcha)
        btnRefreshCaptcha = findViewById(R.id.btnRefreshCaptcha)
        etCaptcha = findViewById(R.id.etCaptcha)
        tilSmsCode = findViewById(R.id.tilSmsCode)
        etSmsCode = findViewById(R.id.etSmsCode)
        tv2faError = findViewById(R.id.tv2faError)
        btnSendSms = findViewById(R.id.btnSendSms)
        btnSubmit2fa = findViewById(R.id.btnSubmit2fa)
        tvLoadError = findViewById(R.id.tvLoadError)
        cardLoginStatus = findViewById(R.id.cardLoginStatus)

        findViewById<MaterialToolbar>(R.id.toolbarLogin).setNavigationOnClickListener { finish() }

        btnLogin.setOnClickListener { submitCredentials() }
        btnRefreshCaptcha.setOnClickListener { refreshCaptcha() }
        ivCaptcha.setOnClickListener { refreshCaptcha() }
        btnSendSms.setOnClickListener { sendSmsCode() }
        btnSubmit2fa.setOnClickListener { submitSecondFactor() }
        btnRestartLogin = findViewById(R.id.btnRestartLogin)
        btnRetry = findViewById(R.id.btnRetry)
        btnRestartLogin.setOnClickListener {
            Log.i(TAG, "用户选择换个账号重新登录,重取一张登录表单")
            secondFactor = null
            loadLoginForm()
        }
        btnRetry.setOnClickListener { loadLoginForm() }
        findViewById<MaterialButton>(R.id.btnWebLogin).setOnClickListener { openWebLogin() }

        // 密码框回车直接提交,省一次点击
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCredentials()
                true
            } else {
                false
            }
        }

        applyInitialState()
    }

    /**
     * 进页面时的初始状态,分三种进入方式(见类注释):
     * - 静默续期交来的二验表单:直接落到二验面板,不再提交账密
     * - 保存的密码被拒:账号预填、密码清空,并把原因摆出来
     * - 其余:预填已保存的账号密码,正常走取表单
     */
    private fun applyInitialState() {
        val handoff = secondFactorFromIntent()

        val creds = CredentialStore(this).load()
        creds?.let {
            etUsername.setText(it.username)
            etPassword.setText(it.password)
            cbSaveCredentials.isChecked = true
        }

        if (handoff != null) {
            // 这一发是主界面静默续期撞上二次验证后转过来的:账密刚被服务端接受,表单里那套
            // 一次性 token 还活着,所以直接进二验面板 —— 用户只需补验证码,不必重输账密
            Log.i(TAG, "从静默续期接手的二次验证表单,直接进二验面板")
            pendingUsername = creds?.username
            pendingPassword = creds?.password
            showSecondFactor(handoff)
            return
        }

        if (intent.getBooleanExtra(EXTRA_INVALID_PASSWORD, false)) {
            // 存的那份密码已经被服务端拒绝过,别再原样提交一次;账号名留着省一次输入
            Log.i(TAG, "保存的密码已失效,清空密码框")
            etPassword.setText("")
            pendingInitialError = getString(R.string.login_saved_password_invalid)
        }
        loadLoginForm()
    }

    /** API 33 起 `getSerializableExtra(String)` 被带类型的重载取代;minSdk 24 所以两条都要走 */
    private fun secondFactorFromIntent(): CasSecondFactorForm? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_SECOND_FACTOR, CasSecondFactorForm::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_SECOND_FACTOR) as? CasSecondFactorForm
        }

    // ── 第 1 步:取表单 ─────────────────────────────────────────────────────

    /**
     * 取一张新表单。[afterError] 用于"表单只能用一次"的场景:账密被拒后旧表单已作废,必须
     * 重新取一张才能再提交,取回来顺带把那句错误显示出来。
     */
    private fun loadLoginForm(afterError: String? = null) {
        if (busy) {
            // 走到这里说明有颗按钮没被 setBusy 关掉(或刚好卡在切换的那一瞬)。**必须留痕**:
            // 静默忽略表现为"点了没反应",正是最难查的那种坏法
            Log.w(TAG, "有请求在飞,忽略这次重取表单")
            return
        }
        form = null
        secondFactor = null
        showOnly(null)
        setBusy(true, getString(R.string.login_loading_form))
        lifecycleScope.launch {
            val load = withContext(Dispatchers.IO) {
                try {
                    cas.fetchLoginForm()
                } catch (e: IOException) {
                    Log.w(TAG, "取登录页网络异常", e)
                    CasFormLoad.Failed(getString(R.string.login_error_network))
                }
            }
            setBusy(false)
            when (load) {
                is CasFormLoad.Ok -> {
                    form = load.form
                    showOnly(groupCredentials)
                    // 进页面时就欠着的那句话(如"保存的密码已失效")只展示一次,之后由
                    // 提交的结果决定显示什么
                    val error = afterError ?: pendingInitialError
                    pendingInitialError = null
                    showCredentialsError(error)
                }

                // 本地 TGT 还活着:CAS 直接给票,不必输账密 —— 这是"能不打搅用户就不打搅"的同一条
                // 原则(与 EcodeApiClient 里"会话没死就不找 CAS 兑票"一致)。
                // 代价要知道:这条路上不会出现账密表单,所以也就勾不到"保存账号密码"。
                // 想开启自动续期就正经登录一次(切换账号会清掉 TGT,表单必然出现)
                is CasFormLoad.Redirected -> {
                    Log.i(TAG, "本地 TGT 仍可用,直接兑票(不展示账密表单)")
                    consumeAndFinish(load.location)
                }

                is CasFormLoad.Failed -> showLoadError(getString(R.string.login_error_load_form, load.detail))
            }
        }
    }

    // ── 第 2 步:提交账密 ───────────────────────────────────────────────────

    private fun submitCredentials() {
        if (busy) return
        val current = form ?: return
        val username = etUsername.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        if (username.isEmpty()) {
            showCredentialsError(getString(R.string.login_error_empty_username))
            return
        }
        if (password.isEmpty()) {
            showCredentialsError(getString(R.string.login_error_empty_password))
            return
        }
        showCredentialsError(null)
        setBusy(true, getString(R.string.login_signing_in))
        lifecycleScope.launch {
            val step = withContext(Dispatchers.IO) {
                try {
                    cas.submitCredentials(current, username, password)
                } catch (e: IOException) {
                    Log.w(TAG, "提交账密网络异常", e)
                    CasStep.Unclear(getString(R.string.login_error_network))
                }
            }
            when (step) {
                is CasStep.Ticket -> {
                    // 账密已被服务端接受,先记下来;存/清留到登录成功后统一收尾
                    pendingUsername = username
                    pendingPassword = password
                    consumeAndFinish(step.location)
                }

                is CasStep.SecondFactor -> {
                    // 走到二验就说明账密已经被接受了,这时才值得记住它
                    pendingUsername = username
                    pendingPassword = password
                    showSecondFactor(step.form)
                }

                is CasStep.BadCredentials -> {
                    setBusy(false)
                    Log.w(TAG, "账密被拒(服务端文案: ${step.serverMessage ?: "未给出"})")
                    loadLoginForm(
                        afterError = step.serverMessage
                            ?: getString(R.string.login_error_bad_credentials)
                    )
                }

                is CasStep.Unclear -> {
                    setBusy(false)
                    Log.w(TAG, "登录响应无法识别: ${step.detail}")
                    loadLoginForm(afterError = getString(R.string.login_error_unclear, step.detail))
                }
            }
        }
    }

    // ── 第 3 步:二次验证 ───────────────────────────────────────────────────

    private fun showSecondFactor(target: CasSecondFactorForm) {
        secondFactor = target
        setBusy(false)
        if (target.mode != MODE_MOBILE && target.mode != MODE_EMAIL) {
            // 微信扫码那类原生做不了:说清楚并直接给逃生口,别让用户对着一个点不动的按钮。
            // 文案不带 mode 本身 —— 那是个英文键(mobile/email/wechatQrCode/unknown),
            // 给用户看"暂不支持 wechatQrCode 验证方式"没有意义
            Log.w(TAG, "二验方式 ${target.mode} 原生不支持,引导去网页登录")
            showLoadError(getString(R.string.login_2fa_unsupported))
            return
        }
        showOnly(groupSecondFactor)
        tv2faError.visibility = View.GONE
        etCaptcha.setText("")
        etSmsCode.setText("")
        tilSmsCode.hint = getString(
            if (target.mode == MODE_EMAIL) R.string.login_2fa_email_hint else R.string.login_2fa_sms_hint
        )
        btnSendSms.text = getString(
            if (target.mode == MODE_EMAIL) R.string.login_2fa_send_email else R.string.login_2fa_send_sms
        )
        refreshCaptcha()
    }

    private fun refreshCaptcha() {
        lifecycleScope.launch {
            ivCaptcha.setImageDrawable(null)
            val bytes = withContext(Dispatchers.IO) {
                try {
                    cas.fetchCaptcha()
                } catch (e: IOException) {
                    Log.w(TAG, "取图形验证码网络异常", e)
                    null
                }
            }
            val bitmap = bytes?.let {
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (bitmap == null) {
                show2faError(getString(R.string.login_2fa_captcha_failed))
            } else {
                ivCaptcha.setImageBitmap(bitmap)
            }
        }
    }

    /**
     * 发短信。**这是全应用唯一会让短信真的发出去的调用**,所以只挂在这个按钮上:
     * 静默续期路径永远不碰它,用户没点就绝不会收到短信。
     */
    private fun sendSmsCode() {
        val target = secondFactor ?: return
        if (busy) return
        val captcha = etCaptcha.text?.toString()?.trim().orEmpty()
        if (captcha.isEmpty()) {
            show2faError(getString(R.string.login_2fa_error_empty_captcha))
            return
        }
        show2faError(null)
        setBusy(true, getString(R.string.login_2fa_sending))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    cas.requestSmsCode(target, captcha)
                } catch (e: IOException) {
                    Log.w(TAG, "请求验证码网络异常", e)
                    CasSmsResult.Failed(getString(R.string.login_error_network))
                }
            }
            setBusy(false)
            when (result) {
                CasSmsResult.Sent -> {
                    Toast.makeText(
                        this@LoginActivity,
                        R.string.login_2fa_send_sent,
                        Toast.LENGTH_LONG,
                    ).show()
                    etSmsCode.requestFocus()
                }

                is CasSmsResult.Rejected -> {
                    // 多数是图形码不对:换一张并清空,让用户直接重来
                    Log.w(TAG, "验证码请求被拒: ${result.message}")
                    show2faError(
                        result.message.ifBlank { getString(R.string.login_2fa_send_rejected) }
                    )
                    etCaptcha.setText("")
                    refreshCaptcha()
                }

                is CasSmsResult.Failed -> {
                    Log.w(TAG, "验证码请求失败: ${result.detail}")
                    show2faError(result.detail)
                }
            }
        }
    }

    private fun submitSecondFactor() {
        val target = secondFactor ?: return
        if (busy) return
        val captcha = etCaptcha.text?.toString()?.trim().orEmpty()
        val code = etSmsCode.text?.toString()?.trim().orEmpty()
        if (captcha.isEmpty()) {
            show2faError(getString(R.string.login_2fa_error_empty_captcha))
            return
        }
        if (code.isEmpty()) {
            show2faError(getString(R.string.login_2fa_error_empty_code))
            return
        }
        show2faError(null)
        setBusy(true, getString(R.string.login_signing_in))
        lifecycleScope.launch {
            val step = withContext(Dispatchers.IO) {
                try {
                    cas.submitSecondFactor(target, captcha, code)
                } catch (e: IOException) {
                    Log.w(TAG, "提交二验网络异常", e)
                    CasStep.Unclear(getString(R.string.login_error_network))
                }
            }
            when (step) {
                is CasStep.Ticket -> consumeAndFinish(step.location)

                // 又给回一张二验页:验证码不对。服务端返回的是一张新表单(token 已换),
                // 必须整体换掉,拿旧的再提交只会一直被拒
                is CasStep.SecondFactor -> {
                    setBusy(false)
                    secondFactor = step.form
                    etCaptcha.setText("")
                    etSmsCode.setText("")
                    show2faError(getString(R.string.login_2fa_error_code))
                    refreshCaptcha()
                }

                // 被弹回登录页:二验这条流程已经作废,退回账密表单重新来
                is CasStep.BadCredentials -> {
                    setBusy(false)
                    Log.w(TAG, "二验被退回登录页,流程作废,重新取表单")
                    loadLoginForm(afterError = getString(R.string.login_2fa_expired))
                }

                is CasStep.Unclear -> {
                    setBusy(false)
                    Log.w(TAG, "二验响应无法识别: ${step.detail}")
                    show2faError(getString(R.string.login_error_unclear, step.detail))
                }
            }
        }
    }

    // ── 收尾 ───────────────────────────────────────────────────────────────

    /** 兑票 + 真校验,与静默路径同一段逻辑;通过才算登录成功 */
    private fun consumeAndFinish(location: String) {
        setBusy(true, getString(R.string.login_verifying))
        lifecycleScope.launch {
            val verified = withContext(Dispatchers.IO) {
                try {
                    cas.consumeTicket(location) { EcodeApiClient(AppHttp.client).hasAuthenticatedSession() }
                } catch (e: IOException) {
                    Log.w(TAG, "兑票后校验网络异常", e)
                    false
                }
            }
            if (!verified) {
                // 必须先解除忙状态:loadLoginForm 开头就挡重复进入,忙着自己调用会被直接忽略,
                // 界面就卡在进度条上不动了
                setBusy(false)
                // 这里**不能**退回 loadLoginForm 重取表单:TGT 还活着时它会再次拿到 Redirected,
                // 于是"兑票失败 → 重取 → 又 Redirected → 再兑票"变成无人按动的死循环
                // (每一次都要打两三个请求)。错误面板的两个按钮正好就是这句话给的出路
                Log.w(TAG, "兑票后会话仍不可用,给错误面板让用户决定重试还是改用网页登录")
                showLoadError(getString(R.string.login_error_consume))
                return@launch
            }
            onLoggedIn()
        }
    }

    private fun onLoggedIn() {
        setBusy(false)
        applyCredentialChoice()
        Log.i(TAG, "登录成功(原生表单),CLEAR_TOP 回主界面自动刷新")
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

    /**
     * 按勾选框落定自动续期:勾选 = 存下这份账密,取消勾选 = 清掉已存的。
     *
     * 三件事合在一起才讲得通:
     * 1. **只有在登录成功之后才执行** —— 失败时不动已存的凭据,更不会把一份打错的密码存进去。
     *    这也是"登录页是唯一凭据录入处"能成立的原因:存下来的每一份账密都被服务端接受过。
     * 2. **取消勾选就是关闭自动续期** —— 否则会出现"我明明取消了勾选,它还在后台替我登录",
     *    而设置页那个"关闭自动续期"只是同一个动作的另一个入口。
     * 3. 这条路径没有用户名时(比如靠还活着的 TGT 直接兑票,根本没输账密)只处理"取消勾选",
     *    不去覆盖或清除已有的凭据。
     */
    private fun applyCredentialChoice() {
        val store = CredentialStore(this)
        if (!cbSaveCredentials.isChecked) {
            if (store.hasCredentials()) {
                store.clear()
                Log.i(TAG, "用户取消勾选保存账密,已关闭自动续期")
            }
            return
        }
        val username = pendingUsername
        val password = pendingPassword
        // 密码不在内存里多留一刻:这一页马上就要 finish()
        pendingUsername = null
        pendingPassword = null
        if (username == null || password == null) return
        if (store.load()?.let { it.username == username && it.password == password } == true) return
        store.save(username, password)
    }

    // ── 界面状态 ────────────────────────────────────────────────────────────

    private fun showOnly(group: View?) {
        groupCredentials.visibility = if (group === groupCredentials) View.VISIBLE else View.GONE
        groupSecondFactor.visibility = if (group === groupSecondFactor) View.VISIBLE else View.GONE
        groupLoadError.visibility = if (group === groupLoadError) View.VISIBLE else View.GONE
    }

    private fun showLoadError(message: String) {
        showOnly(groupLoadError)
        tvLoadError.text = message
    }

    private fun showCredentialsError(message: String?) {
        tvError.text = message.orEmpty()
        tvError.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun show2faError(message: String?) {
        tv2faError.text = message.orEmpty()
        tv2faError.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 忙状态只有一个入口,两个面板的控件一起管。
     *
     * 分成两个 setter(各管各的面板)踩过坑:二验那一步兑票失败后会退回账密表单,若"解除忙"
     * 只恢复账密控件,下次再进二验面板时那些按钮还停在 disabled 上,点不动。
     */
    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        progressLogin.visibility = if (value) View.VISIBLE else View.GONE
        tvLoginProgress.text = status.orEmpty()
        tvLoginProgress.visibility = if (value && !status.isNullOrEmpty()) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !value
        etUsername.isEnabled = !value
        etPassword.isEnabled = !value
        cbSaveCredentials.isEnabled = !value
        btnRefreshCaptcha.isEnabled = !value
        etCaptcha.isEnabled = !value
        btnSendSms.isEnabled = !value
        etSmsCode.isEnabled = !value
        btnSubmit2fa.isEnabled = !value
        // 这两颗只是重取表单,而 loadLoginForm 在 busy 时是直接忽略的 —— 不一起关掉,
        // 用户点下去就是"毫无反应"
        btnRestartLogin.isEnabled = !value
        btnRetry.isEnabled = !value
    }

    /**
     * 逃生口:整个应用是屏幕抓取,校方改版可能让原生解析整个失灵,而"登录"是唯一不能
     * 失灵的功能,所以这一页底部常驻一个入口。它同时是"学校要求的验证方式原生做不了"
     * (如微信扫码)时的唯一出路。
     */
    private fun openWebLogin() {
        Log.i(TAG, "用户选择网页登录(兼容模式)")
        startActivity(Intent(this, WebLoginActivity::class.java))
        finish()
    }
}
