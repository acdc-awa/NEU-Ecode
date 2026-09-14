package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/** 静默重登结果:布尔表达不了"要不要让用户去输短信码"这件事,所以四态分开 */
sealed class SilentLoginOutcome {
    /** 已换到新 TGT 并落回 ecode */
    object Success : SilentLoginOutcome()

    /**
     * 账号密码通过了,但校方要求短信二次验证——已停在"发短信之前",用户没被打扰也没被计费。
     *
     * 带上 [form] 是有意的:这张二验表单**已经被服务端接受了**(token 由那一次账密提交换来),
     * 交给交互登录页继续用,用户就只需补一个验证码 —— 不必让登录页再提交一次同样的账密,
     * 这正是"登录与自动续期是同一件事"的落点。
     */
    data class NeedSecondFactor(val form: CasSecondFactorForm) : SilentLoginOutcome()

    /** 账号密码被拒(或提交后又被弹回登录表单) */
    object Rejected : SilentLoginOutcome()

    /** 没有保存账密 / 冷却中 / 连续被拒已停用 */
    object Unavailable : SilentLoginOutcome()

    /** 网络异常或页面结构无法识别 */
    data class Error(val detail: String) : SilentLoginOutcome()
}

/**
 * 静默重登的护栏与账本。真正的登录动作在 [SilentRelogin] 里做(直接走 CAS 协议,不经过界面),
 * 这里只管"该不该试"和"记下结果"。
 *
 * 三道护栏:
 * - 单飞:一次只能有一个静默登录在跑([claim]/[release]),否则二维码循环与余额刷新两个
 *   触发点会对同一个账号并发提交两次账密
 * - 冷却:两次尝试之间至少 [COOLDOWN_MS];撞到短信验证码后冷却拉长到
 *   [SECOND_FACTOR_COOLDOWN_MS],避免在用户还没做二验时反复撩拨
 * - 熔断:连续被拒 [MAX_CONSECUTIVE_REJECTIONS] 次就彻底停手。存错密码 + 后台自动重试
 *   = 反复失败登录,这是唯一可能把学校账号搞进风控/锁号的路径,必须自己先停下
 *
 * 注意只有 [SilentLoginOutcome.Rejected] 计入熔断。短信验证码和网络异常都不算——
 * 它们不是凭据错了,把它们也熔断会让功能在校外或弱网下一次就被永久关掉。
 */
object SilentLogin {

    private const val TAG = "SilentLogin"

    private const val MAX_CONSECUTIVE_REJECTIONS = 2
    private const val COOLDOWN_MS = 60_000L
    private const val SECOND_FACTOR_COOLDOWN_MS = 10 * 60_000L

    /** 单飞占位的自愈时限:超过它就把占位当成陈旧的释放掉 */
    private const val IN_FLIGHT_TIMEOUT_MS = 90_000L

    private const val PREF_NAME = "ecode_silent_login"
    private const val KEY_REJECTIONS = "rejections"
    private const val KEY_LAST_ATTEMPT = "lastAttemptAt"
    private const val KEY_COOLDOWN_UNTIL = "cooldownUntil"
    private const val KEY_LAST_OUTCOME = "lastOutcome"

    enum class Decision { ALLOW, NO_CREDENTIALS, IN_FLIGHT, COOLDOWN, DISABLED }

    /** 单飞占位的时间戳,0 = 没有静默重登在跑 */
    @Volatile
    private var inFlightAt = 0L

    /**
     * 占住单飞位。必须在**发请求之前**调用:二维码循环与余额刷新可能同时判定会话失效,
     * 若两者都进到提交账密那一步,同一个账号会被并发提交两次——正是护栏要拦的事。
     */
    fun claim() {
        inFlightAt = System.currentTimeMillis()
    }

    /** 释放单飞位(拿到结果、或登录页被销毁时) */
    fun release() {
        inFlightAt = 0L
    }

    /**
     * 是否有一次静默重登在跑。带超时自愈:万一把占位之后登录页没起来(进程被回收等),
     * 也不至于把功能永久卡在"冷却中"。
     */
    private fun inFlight(): Boolean {
        val at = inFlightAt
        if (at == 0L) return false
        if (System.currentTimeMillis() - at < IN_FLIGHT_TIMEOUT_MS) return true
        Log.w(TAG, "静默重登占位已陈旧,自动释放")
        inFlightAt = 0L
        return false
    }

    /**
     * 该不该试。三道门:没存账密、已有一发在跑、冷却/熔断。
     *
     * 冷却与熔断只约束**后台自动重试**——存错密码 + 自动重试是唯一可能把学校账号搞进
     * 风控的路径,所以由应用自己克制。用户手动的那些动作(登录页、设置页)不走这里,
     * 它们走的是"提交一份账密并当场看服务端怎么答",不需要也不该被冷却挡住。
     */
    fun decide(context: Context, store: CredentialStore): Decision {
        if (inFlight()) return Decision.IN_FLIGHT
        if (!store.hasCredentials()) return Decision.NO_CREDENTIALS
        val p = prefs(context)
        if (p.getInt(KEY_REJECTIONS, 0) >= MAX_CONSECUTIVE_REJECTIONS) return Decision.DISABLED
        if (System.currentTimeMillis() < p.getLong(KEY_COOLDOWN_UNTIL, 0L)) return Decision.COOLDOWN
        return Decision.ALLOW
    }

    /** 连续被拒到熔断:这份账密已被服务端否定,再试也只是重复失败 */
    fun disabled(context: Context): Boolean =
        prefs(context).getInt(KEY_REJECTIONS, 0) >= MAX_CONSECUTIVE_REJECTIONS

    fun record(context: Context, outcome: SilentLoginOutcome) {
        release()
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val edit = p.edit()
            .putLong(KEY_LAST_ATTEMPT, now)
            .putString(KEY_LAST_OUTCOME, nameOf(outcome))
        when (outcome) {
            // 成功也给一段冷却:登录页报"成功"只证明它到了 ecode 主机且本地有 SESSION cookie,
            // 证明不了服务端那一侧的会话真的活着。万一是个假成功,没有这段冷却就会
            // "拉起登录页→自报成功→回来又失效"无限重来(表现为主界面不停闪)
            SilentLoginOutcome.Success ->
                edit.putInt(KEY_REJECTIONS, 0).putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)

            SilentLoginOutcome.Rejected ->
                edit.putInt(KEY_REJECTIONS, p.getInt(KEY_REJECTIONS, 0) + 1)
                    .putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)

            is SilentLoginOutcome.NeedSecondFactor ->
                edit.putLong(KEY_COOLDOWN_UNTIL, now + SECOND_FACTOR_COOLDOWN_MS)

            is SilentLoginOutcome.Error ->
                edit.putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)

            SilentLoginOutcome.Unavailable -> Unit
        }
        edit.apply()
        Log.i(TAG, "静默重登结果: ${nameOf(outcome)}")
    }

    /** 保存/清除账密时调用:换过凭据就重新给满次数 */
    fun reset(context: Context) {
        prefs(context).edit()
            .putInt(KEY_REJECTIONS, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .apply()
    }

    /**
     * 上一次尝试的结局名([nameOf] 的取值),用于把"自动续期为什么没成"讲给用户听。
     * 界面只用得到其中的 needSecondFactor / rejected 两种,其余当"没有可说的"处理。
     */
    fun lastOutcome(context: Context): String? =
        prefs(context).getString(KEY_LAST_OUTCOME, null)

    /** 诊断用:一行状态,不含任何凭据 */
    fun describe(context: Context, store: CredentialStore): String {
        val p = prefs(context)
        val until = p.getLong(KEY_COOLDOWN_UNTIL, 0L)
        val now = System.currentTimeMillis()
        val cooldown = if (until > now) "冷却中(${(until - now) / 1000}s)" else "无冷却"
        // 上次结果留在账本里:静默换 TGT 是"成功"还是"撞上短信验证",Toast 一闪就没了,
        // 这行字是事后还能回看的那个答案
        val last = p.getString(KEY_LAST_OUTCOME, null)
        val lastText = if (last == null) {
            "无"
        } else {
            val ago = now - p.getLong(KEY_LAST_ATTEMPT, 0L)
            "$last(${ago / 1000}s 前)"
        }
        return "已存账密=${store.hasCredentials()} 账号=${store.username() ?: "-"} " +
            "连续被拒=${p.getInt(KEY_REJECTIONS, 0)}/$MAX_CONSECUTIVE_REJECTIONS $cooldown " +
            "上次结果=$lastText"
    }

    fun nameOf(outcome: SilentLoginOutcome): String = when (outcome) {
        SilentLoginOutcome.Success -> "success"
        is SilentLoginOutcome.NeedSecondFactor -> "needSecondFactor"
        SilentLoginOutcome.Rejected -> "rejected"
        SilentLoginOutcome.Unavailable -> "unavailable"
        is SilentLoginOutcome.Error -> "error"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
