package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 自动续期用的账号密码(用户显式保存,默认没有)。
 *
 * 为什么要存密码:CASTGC 只在输账密时签发、寿命 2 小时,且任何兑票都不给它续期,所以
 * "免密续期"在 2 小时后必然失败。存下账密后,TGT 失效时可以重走一次输账密的 POST 换一张
 * 新的 TGT,把"2 小时后必须手动登录"变成"校方不再要短信验证时就一直自动"。
 *
 * **唯一的写入者是登录页**(并且只在服务端接受了这份账密之后才写),登录页上那个勾选框就是
 * 自动续期的开关——设置页不再单独提供录入对话框。理由:在设置页单独存账密时,打错一个字母
 * 也会被存进去,随后静默续期拿它连试两次不中直接熔断;而"登录成功才存"从结构上排除了
 * 存下错密码的可能。
 *
 * 存储:EncryptedSharedPreferences,主密钥在 AndroidKeyStore 里(不可导出);应用
 * allowBackup=false,所以这份数据不会随备份离开设备。密码不写日志、不进界面、不进诊断信息。
 *
 * 需要让用户知道的代价:密码会被交给 CAS 登录页的 JS(页面的 rsa = RSA(账号+密码) 必须
 * 用明文算)。另外若在别处改了密码,这里保存的旧密码会持续失败——连续被拒达上限后会自动
 * 停止静默尝试并清除密码,只留下账号名(见 [SilentLogin])。
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 密钥或存储损坏时置 null,静默续期整体降级为"不可用",不影响其余功能。
     * 缓存到 companion:MasterKey 要过一遍 AndroidKeyStore,而调用方(登录页每次
     * onPageFinished 的预填)会反复构造本类,不该每次都去敲一次硬件密钥库。
     */
    private val prefs: SharedPreferences? by lazy {
        sharedPrefs ?: createPrefs(appContext).also { sharedPrefs = it }
    }

    data class Credentials(val username: String, val password: String)

    fun load(): Credentials? {
        val p = prefs ?: return null
        val username = p.getString(KEY_USERNAME, null)
        val password = p.getString(KEY_PASSWORD, null)
        if (username.isNullOrBlank() || password.isNullOrEmpty()) return null
        return Credentials(username, password)
    }

    /**
     * 只用于界面展示与预填"是哪个账号";密码永不外露。
     * 直接读账号名字段(而不是经 [load]),这样密码被单独清掉之后仍能预填账号。
     */
    fun username(): String? =
        prefs?.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }

    /** 一份可用的账密 = 账号与密码都在。自动续期的前提 */
    fun hasCredentials(): Boolean = load() != null

    fun save(username: String, password: String) {
        prefs?.edit()
            ?.putString(KEY_USERNAME, username)
            ?.putString(KEY_PASSWORD, password)
            ?.apply()
        // 换了账密就等于换了一次机会:此前"连续被拒"的计数不该继续压着新的凭据
        SilentLogin.reset(appContext)
        Log.i(TAG, "已保存自动续期账密(账号名可入日志,密码不入)")
    }

    /**
     * 只删密码、留下账号名。
     *
     * 用在"连续被拒已熔断"那一刻:这份密码已经被服务端否定过两次,再留着它只会在每次打开
     * 登录页时预填一个已知错误的密码。账号名留着有用——用户重新登录时只需补密码。
     */
    fun clearPassword() {
        prefs?.edit()?.remove(KEY_PASSWORD)?.apply()
        SilentLogin.reset(appContext)
        Log.i(TAG, "已清除保存的密码(账号名保留)")
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        SilentLogin.reset(appContext)
        Log.i(TAG, "已清除自动续期账密")
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val PREF_NAME = "ecode_credential"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"

        /** EncryptedSharedPreferences 实例缓存(见 [prefs] 的说明) */
        @Volatile
        var sharedPrefs: SharedPreferences? = null

        fun createPrefs(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "加密存储不可用,静默续期功能停用", e)
            null
        }
    }
}
