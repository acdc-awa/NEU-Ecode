package com.neboer.ecode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 静默重登:用本地保存的账密换一次 TGT,**全程不经过任何界面**。
 *
 * 上一版是用一个"不可见的登录页 Activity"驱动 WebView 完成的,代价是:要一个透明主题、
 * 要 `alpha=0`、要 `FLAG_NOT_TOUCHABLE`、要清亮度补丁、要 Activity 结果回传,而且页面自身
 * 的提交脚本一旦没被触发就会"自报成功"——无限闪屏就是这么来的。现在登录协议由
 * [CasAuthClient] 直接走,这些全部不需要:没有 Activity,就没有生命周期耦合,
 * 也不会因为窗口可见性产生任何闪烁。
 *
 * 职责边界:[SilentLogin] 管"该不该试"(冷却/熔断/单飞),本类管"怎么试一次",
 * [CasAuthClient] 管协议。三个都不含界面逻辑。
 *
 * 它是"自动续期"的执行体,而不是一个独立功能:需要验证码时它把那张已被服务端接受的二验
 * 表单原样交还给调用方,由界面接手 —— 于是"自动"与"手动"之间只差一个验证码,而不是
 * 两套流程。
 */
object SilentRelogin {

    private const val TAG = "SilentRelogin"

    /**
     * 跑一次静默重登。返回结局,并把它记进 [SilentLogin] 的账本(护栏据此决定下次还让不让试)。
     *
     * 两个触发点(二维码会话失效、余额会话失效)可能同时判定,所以进来先看账本的单飞位,
     * 已经在跑就直接返回 [SilentLoginOutcome.Unavailable],由调用方按自己的方式收场。
     */
    suspend fun run(
        context: Context,
        store: CredentialStore,
    ): SilentLoginOutcome =
        // NonCancellable:发起方(主界面/设置页)的 lifecycleScope 可能在尝试途中被销毁,
        // 但这一发不能半路丢掉 —— 丢掉就意味着"结局没记账 + 单飞位泄漏到超时",
        // 而账本错一次,熔断与冷却就都不可信了。整个流程本身是有界的(15–20s 超时)
        withContext(Dispatchers.IO + NonCancellable) {
            when (SilentLogin.decide(context, store)) {
                SilentLogin.Decision.ALLOW -> Unit
                SilentLogin.Decision.NO_CREDENTIALS,
                SilentLogin.Decision.IN_FLIGHT,
                SilentLogin.Decision.COOLDOWN,
                SilentLogin.Decision.DISABLED,
                -> {
                    Log.i(TAG, "静默重登未执行(${SilentLogin.describe(context, store)})")
                    return@withContext SilentLoginOutcome.Unavailable
                }
            }

            val creds = store.load() ?: return@withContext SilentLoginOutcome.Unavailable
            // 占位要在发请求之前:两个触发点同时进来时,后到的必须看到"已经有一发在跑"
            SilentLogin.claim()

            val ecode = EcodeApiClient(AppHttp.client)
            val outcome = try {
                CasAuthClient(AppHttp.client).loginSilently(creds.username, creds.password) {
                    // "成功"的定义是服务端还认这个会话,而不是本地 cookie 里有东西:
                    // 一次真校验(qr-code 200),不续期也不改状态
                    ecode.hasAuthenticatedSession()
                }
            } catch (e: IOException) {
                Log.w(TAG, "静默重登网络异常", e)
                SilentLoginOutcome.Error(e.javaClass.simpleName + ": " + (e.message ?: ""))
            } catch (e: Exception) {
                // 解析/加解密等任何意外都不该穿透到界面
                Log.e(TAG, "静默重登异常", e)
                SilentLoginOutcome.Error(e.javaClass.simpleName)
            }

            SilentLogin.record(context, outcome)
            // 熔断即"这份账密已被服务端否定":只删密码、留下账号名,免得每次打开登录页
            // 都把那个已知错误的密码预填回去,也免得它一直被重放
            if (outcome == SilentLoginOutcome.Rejected && SilentLogin.disabled(context)) {
                Log.w(TAG, "连续被拒已熔断,清除保存的密码(保留账号名用于预填)")
                store.clearPassword()
            }
            Log.i(TAG, "静默重登结束: ${SilentLogin.nameOf(outcome)}")
            outcome
        }
}
