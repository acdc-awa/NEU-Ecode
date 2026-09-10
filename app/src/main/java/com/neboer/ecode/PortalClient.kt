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
 * 两次请求只需 SESS_ID cookie + Accept: application/json(浏览器还带了
 * X-Requested-With: XMLRequest,非必需,这里照带以贴近浏览器指纹)。
 *
 * 会话:走门户自己的 SSO 入口 cas_login/1(由它拼 service 去 CAS,CASTGC 靠共享
 * CookieJar 过站),兑票后 GET /portal/ 签发 SESS_ID,再 GET msg/index 把 SESS_ID
 * 的 24h 窗口推满。直接对 tpass 传 service=根路径无效——根路径不消费 ticket,
 * SESS_ID 也无人签发(2026-09-09 webview-demo 实测修正,commit 77695a9)。
 *
 * 会话寿命(2026-09 抓包结论,原始报文见 .har/ 与 AGENTS.md):
 * - 余额接口只认 SESS_ID + CK_LC + CK_VL,而这三者只能靠 CASTGC 兑票换来
 * - CASTGC 只在输账密登录时签发,Max-Age=7200(2 小时),之后兑票不会续期;
 *   非校园网重新登录还要过短信二次验证,静默刷 CASTGC 走不通
 * - 余额接口自身不刷新 SESS_ID,只有 msg/index 这类端点会把 SESS_ID 重发成 +24h
 *
 * 所以这里不靠 CASTGC 续命,而是靠 [keepSessionAlive] 在应用前台周期性地打
 * msg/index,让门户会话的 24h 窗口一直往后滑——只要用户 24 小时内开过一次应用,
 * 门户会话就不会失效,余额也就不需要重新登录。真的滑没了(SESS_ID 与 CASTGC
 * 同时不在),只能返回 [BalanceResult.SessionExpired] 让 UI 引导重新登录。
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
        private const val CAS_HOST = "pass.neu.edu.cn"
    }

    /** 兑票结果:OK=门户会话已建立;AUTH_EXPIRED=CASTGC失效;FAILED=链路变化等其他原因 */
    private enum class EstablishResult { OK, AUTH_EXPIRED, FAILED }

    /**
     * 兑票会改写 CK_LC/CK_VL 并消费票据,而余额刷新与前台保活可能同时触发,
     * 所以串行化"建立会话"这一段;读余额的快路径不需要它。
     */
    private val sessionLock = ReentrantLock()

    // API 不跟随重定向:未登录时门户会 302 去 CAS,直接按失败处理而不是落到登录页 HTML
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
            if (isSessionAlive()) {
                Log.d(TAG, "门户会话保活成功(SESS_ID窗口已顺延)")
                return true
            }
            sessionLock.lock()
            try {
                // 拿到锁后会话可能已被余额流程建好,再确认一次
                if (isSessionAlive()) return true
                if (!WebViewCookieJar.hasCastgc()) {
                    Log.w(TAG, "门户会话保活失败:SESS_ID 已失效且 CASTGC 不在,需重新登录")
                    return false
                }
                Log.i(TAG, "门户会话已失效,经cas_login兑票重建")
                establishViaCas() == EstablishResult.OK && isSessionAlive()
            } finally {
                sessionLock.unlock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "门户会话保活异常", e)
            false
        }
    }

    /** 打一发 msg/index:2xx 说明门户会话有效(顺带把 SESS_ID 的 24h 窗口推后) */
    private fun isSessionAlive(): Boolean = getJson(KEEPALIVE_URL) != null

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
        val response = followClient.newCall(
            Request.Builder().url(CAS_LOGIN_ENTRY_URL).header("User-Agent", UserAgent.current).get().build()
        ).execute()
        val landed = response.request.url
        response.close()
        Log.d(TAG, "CAS兑票落地: $landed")
        // 没有有效 TGT 时 CAS 会把 tpass 登录页(200 HTML)直接返回,不会再 302 出票
        if (landed.host != PORTAL_HOST) {
            return if (landed.host == CAS_HOST) EstablishResult.AUTH_EXPIRED else EstablishResult.FAILED
        }
        followClient.newCall(
            Request.Builder().url(PORTAL_HOME_URL).header("User-Agent", UserAgent.current).get().build()
        ).execute().close()
        followClient.newCall(
            Request.Builder().url(KEEPALIVE_URL).header("User-Agent", UserAgent.current).get().build()
        ).execute().close()
        return EstablishResult.OK
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
