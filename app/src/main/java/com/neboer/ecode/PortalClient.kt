package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 门户(personal.neu.edu.cn)"个人数据"卡片的余额数据源——唯一余额接口。
 *
 * 接口流程(见 .har/personal.neu.edu.cn.har):
 * 1. GET /portal/personal/frontend/data/items?type=personal_data
 *    → {"e":0,"d":{"data":[{"key":"card.balance","id":"<服务端签发的长哈希>",...},...]}}
 *    按 key 找到校园卡余额条目取其 id(动态获取,勿硬编码);同一接口还有
 *    net.balance(网费余额),以后要加同样按 key 查 detail 即可
 * 2. GET /portal/personal/frontend/data/detail?id=<id>
 *    → {"e":0,"m":"操作成功","d":{"data":{"value":"50.33","unit":"元"}}}
 * 两次请求需要 CK_LC + CK_VL 两个 cookie(SESS_ID 不参与校验,见下);Accept: application/json
 * 即可,浏览器还带了 X-Requested-With: XMLRequest,非必需,这里照带以贴近浏览器指纹。
 * 鉴权失败不会给 HTTP 错误码,而是 200 + {"e":10013,"m":"登录信息已失效，请重新登录"}。
 *
 * 会话:走门户自己的 SSO 入口 cas_login/1(由它拼 service 去 CAS,CASTGC 靠共享
 * CookieJar 过站),兑票那一跳下发 CK_LC/CK_VL。直接对 tpass 传 service=根路径无效
 * ——根路径不消费 ticket(2026-09-09 webview-demo 实测修正,commit 0c6df01)。
 *
 * 鉴权模型(2026-09-10 控制变量实测,握手细节见 .har/ 与 AGENTS.md):
 * - 余额 data 接口只认 CK_LC + CK_VL,两个缺一不可;SESS_ID 完全不参与校验
 *   (伪造 SESS_ID + 真 CK_LC/CK_VL 照样返回完整数据)
 * - CK_LC/CK_VL 只在 cas_login/1 兑票那一跳下发,之后没有任何端点重发它们
 *   (扫过 15 个门户端点,只有 msg/index 重发 SESS_ID,而 SESS_ID 不是凭据)
 * - 所以凭据没有滑动续期手段:唯一重新获得的途径是用有效 CASTGC 再兑一次票,
 *   且兑出的是全新的一对(旧的那对不会因此续期)
 * - CASTGC 只在输账密登录时签发,Max-Age=7200(2 小时),之后兑票不会续期;
 *   没有 CASTGC 时门户 SSO 入口只会 302 回 CAS 登录页,所以非校园网只能重新登录
 *   (还要过短信二次验证)
 *
 * 因此 [keepSessionAlive] 的价值边界很清楚:CASTGC 仍有效(登录后 2 小时内)时,它能及早
 * 发现凭据失效并静默重建,让用户不必为此重新登录;超过 2 小时就只剩重新登录一条路。
 * 它顺带打的 msg/index 会把 SESS_ID 窗口推后 24h,但 SESS_ID 不参与余额鉴权,那一步
 * 是尽力而为,不是保命手段。凭据彻底不可用时返回 [BalanceResult.SessionExpired]。
 *
 * 历史:此前门户与一卡通(ecard)余额数值不同步、门户读数偏大,故主界面曾以
 * EcardClient 为准;2026-09 两侧数据已同步,ecard 解析(含 Jsoup 依赖)整体废弃。
 */
class PortalClient(
    private val client: OkHttpClient
) : BalanceSource {

    companion object {
        private const val TAG = "PortalClient"
        private const val ITEMS_URL =
            "https://personal.neu.edu.cn/portal/personal/frontend/data/items?type=personal_data"
        private const val DETAIL_URL =
            "https://personal.neu.edu.cn/portal/personal/frontend/data/detail"
        private const val BALANCE_KEY = "card.balance"
        // 门户自己的 CAS 入口:ticket 消费端点是 cas_login/1,service 由服务端拼
        private const val CAS_LOGIN_ENTRY_URL =
            "https://personal.neu.edu.cn/portal/manage/common/cas_login/1?to_bind=0&redirect=https%3A%2F%2Fpersonal.neu.edu.cn%2Fportal"
        // SESS_ID 由 /portal/ 页面签发,兑票后需预热一发
        private const val PORTAL_HOME_URL = "https://personal.neu.edu.cn/portal/"
        // 门户自己首页会轮询的消息接口:实测只有它会把 SESS_ID 重发成 Max-Age=86400,
        // 因此用它做会话保活(余额接口 items/detail 不刷新 SESS_ID)
        private const val KEEPALIVE_URL =
            "https://personal.neu.edu.cn/portal/ucs/frontend/msg/index?keyword=&ucs_type=&source=&starttime=&page=1&pagesize=10&status=2"
        private const val PORTAL_HOST = "personal.neu.edu.cn"
    }

    /** 兑票结果:OK=门户会话已建立;AUTH_EXPIRED=CASTGC失效;FAILED=链路变化等其他原因 */
    private enum class EstablishResult { OK, AUTH_EXPIRED, FAILED }

    /**
     * 兑票会改写 CK_LC/CK_VL 并消费票据,而余额刷新与前台保活可能同时触发,
     * 所以串行化"建立会话"这一段;读余额的快路径不需要它。
     */
    private val sessionLock = ReentrantLock()

    // API 不跟随重定向:未登录时门户会 302 去 CAS,直接按失败处理而不是落到登录页 HTML
    /** 兑票统一交给 CAS 客户端(共享 cookie 罐里的 CASTGC 是唯一凭据) */
    private val cas = CasAuthClient(client)

    private val apiClient = client.newBuilder()
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val followClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun fetchBalance(): BalanceResult {
        return try {
            fetchCardBalance()?.let { return BalanceResult.Success(it) }
            ensureSessionAndFetch()
        } catch (e: IOException) {
            Log.w(TAG, "门户网络不可达", e)
            BalanceResult.NetworkUnreachable
        } catch (e: Exception) {
            Log.e(TAG, "门户余额获取异常", e)
            BalanceResult.Failed
        }
    }

    /**
     * 会话保活:应用在前台时周期性调用,把门户 SESS_ID 的 24h 窗口往后推。
     * 会话已失效时尝试用 CASTGC 兑票重建。返回 false 表示门户会话彻底不可用(需重新登录)。
     * 不抛异常,失败只记日志——保活是尽力而为,不能影响界面。
     */
    fun keepSessionAlive(): Boolean {
        return try {
            if (isCredentialAlive()) {
                slideSessionWindow()
                Log.d(TAG, "门户凭据仍有效,保活顺延 SESS_ID 窗口")
                return true
            }
            sessionLock.lock()
            try {
                // 等锁期间余额流程可能已经重建好会话,再确认一次
                if (isCredentialAlive()) {
                    slideSessionWindow()
                    return true
                }
                if (!WebViewCookieJar.hasCastgc()) {
                    Log.w(TAG, "门户凭据已失效且 CASTGC 不在,需重新登录")
                    return false
                }
                Log.i(TAG, "门户凭据已失效,经cas_login兑票重建")
                establishViaCas() == EstablishResult.OK && isCredentialAlive()
            } finally {
                sessionLock.unlock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "门户会话保活异常", e)
            false
        }
    }

    /**
     * 门户凭据(CK_LC/CK_VL)是否仍然有效——必须用真正校验凭据的端点(items)判定。
     *
     * 2026-09-10 实测:门户鉴权失败返回的是 HTTP 200 + body e=10013(不是 HTTP 错误码),
     * 所以看状态码一律"成功";msg/index 更差,它对已登出作废的凭据也返回 e=0。
     * 两者都不能当探针,只有 items/detail 这类数据端点会真的校验(伪造或作废的凭据都返回 e=10013)。
     */
    private fun isCredentialAlive(): Boolean = probeCredential().first == 0

    /** 打一发 items 并回报 e 字段;-1 表示网络或解析失败。e=0 才是凭据有效 */
    private fun probeCredential(): Pair<Int, String> {
        val body = getJson(ITEMS_URL) ?: return -1 to "网络不可达"
        return try {
            val json = JSONObject(body)
            json.optInt("e", -1) to json.optString("m")
        } catch (e: Exception) {
            Log.w(TAG, "items 响应解析失败: ${body.take(200)}", e)
            -1 to "响应解析失败"
        }
    }

    /** 只有 msg/index 会把 SESS_ID 重发成 Max-Age=86400;它不影响余额凭据,这里只是顺延窗口 */
    private fun slideSessionWindow() {
        getJson(KEEPALIVE_URL)
    }

    private fun ensureSessionAndFetch(): BalanceResult {
        if (!WebViewCookieJar.hasCastgc()) {
            Log.w(TAG, "门户会话无效且 CASTGC 已失效(登录超过2小时未续期),需重新登录")
            return BalanceResult.SessionExpired
        }
        sessionLock.lock()
        try {
            // 等锁期间保活流程可能已经重建好会话,先补一次余额
            fetchCardBalance()?.let { return BalanceResult.Success(it) }
            Log.i(TAG, "门户会话无效,经cas_login入口兑票建立会话(靠CASTGC静默换票)")
            return when (establishViaCas()) {
                EstablishResult.OK ->
                    fetchCardBalance()?.let { BalanceResult.Success(it) } ?: BalanceResult.Failed
                EstablishResult.AUTH_EXPIRED -> {
                    Log.w(TAG, "CASTGC 已失效(落回tpass登录页),无法静默恢复,需重新登录")
                    BalanceResult.SessionExpired
                }
                EstablishResult.FAILED -> {
                    Log.w(TAG, "CAS兑票未落到门户,门户链路可能已变化")
                    BalanceResult.Failed
                }
            }
        } finally {
            sessionLock.unlock()
        }
    }

    /**
     * 靠共享 CookieJar 里的 CASTGC 走门户自己的 SSO 入口兑票:
     * cas_login/1 → 302 tpass/login?service=… → 302 cas_login/1?…&ticket=ST
     * → 下发 CK_LC/CK_VL → 落回 /portal;SESS_ID 由 /portal/ 签发,补一发预热,
     * 再用 msg/index 把它的 24h 窗口推满。
     */
    private fun establishViaCas(): EstablishResult {
        return when (cas.establishSession(CAS_LOGIN_ENTRY_URL, PORTAL_HOST)) {
            CasAuthClient.Establish.OK -> {
                followClient.newCall(
                    Request.Builder().url(PORTAL_HOME_URL).header("User-Agent", UserAgent.current).get().build()
                ).execute().close()
                followClient.newCall(
                    Request.Builder().url(KEEPALIVE_URL).header("User-Agent", UserAgent.current).get().build()
                ).execute().close()
                EstablishResult.OK
            }
            // 没有有效 TGT 时 CAS 会把 tpass 登录页直接返回,不再 302 出票
            CasAuthClient.Establish.NO_TGT -> EstablishResult.AUTH_EXPIRED
            CasAuthClient.Establish.ELSEWHERE -> EstablishResult.FAILED
        }
    }

    /** 返回 card.balance 的数值(如 "50.33");未登录/接口报错/结构变化返回 null */
    private fun fetchCardBalance(): String? {
        val id = findBalanceItemId() ?: return null
        val body = getJson("$DETAIL_URL?id=$id") ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("e", -1) != 0) {
                Log.w(TAG, "detail接口报错: e=${json.optInt("e")} m=${json.optString("m")}")
                return null
            }
            val value = json.getJSONObject("d").getJSONObject("data").optString("value")
            value.takeIf { it.isNotBlank() }?.also { Log.i(TAG, "门户余额获取成功: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "detail响应解析失败: ${body.take(200)}", e)
            null
        }
    }

    /** 从 items 列表按 key 找到余额条目的 id;未登录或结构变化返回 null */
    private fun findBalanceItemId(): String? {
        val body = getJson(ITEMS_URL) ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("e", -1) != 0) {
                Log.w(TAG, "items接口报错: e=${json.optInt("e")} m=${json.optString("m")}")
                return null
            }
            val items = json.getJSONObject("d").getJSONArray("data")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("key") == BALANCE_KEY) {
                    return item.optString("id").takeIf { it.isNotEmpty() }
                }
            }
            Log.w(TAG, "items中未找到key=$BALANCE_KEY (共${items.length()}条)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "items响应解析失败: ${body.take(200)}", e)
            null
        }
    }

    private fun getJson(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgent.current)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://personal.neu.edu.cn/portal")
            .header("X-Requested-With", "XMLRequest")
            .get()
            .build()
        val response = apiClient.newCall(request).execute()
        val body = response.body?.string()
        Log.d(TAG, "GET $url → HTTP ${response.code}, ${body?.length ?: 0}字")
        return if (response.isSuccessful) body else null
    }
}
