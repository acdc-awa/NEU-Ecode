package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

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
 * CookieJar 过站),兑票后 GET /portal/ 签发 SESS_ID。直接对 tpass 传
 * service=根路径 无效——根路径不消费 ticket,SESS_ID 也无人签发
 * (2026-09-09 webview-demo 实测修正,commit 77695a9)。
 * CASTGC 失效时无法静默恢复,返回 Failed 由 UI 引导重新登录。
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
        private const val PORTAL_HOST = "personal.neu.edu.cn"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

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
            establishSessionAndFetch()
        } catch (e: IOException) {
            Log.w(TAG, "门户网络不可达", e)
            BalanceResult.NetworkUnreachable
        } catch (e: Exception) {
            Log.e(TAG, "门户余额获取异常", e)
            BalanceResult.Failed
        }
    }

    private fun establishSessionAndFetch(): BalanceResult {
        Log.i(TAG, "门户会话无效,经cas_login入口兑票建立会话(靠CASTGC静默换票)")
        if (establishViaCas()) {
            fetchCardBalance()?.let { return BalanceResult.Success(it) }
        }
        Log.w(TAG, "CAS兑票未落到门户或未取到余额(CASTGC可能失效),无法静默恢复,需重新登录")
        return BalanceResult.Failed
    }

    /**
     * 靠共享 CookieJar 里的 CASTGC 走门户自己的 SSO 入口兑票:
     * cas_login/1 → 302 tpass/login?service=… → 302 cas_login/1?…&ticket=ST
     * → 下发 CK_LC/CK_VL → 落回 /portal;SESS_ID 由 /portal/ 签发,补一发预热。
     */
    private fun establishViaCas(): Boolean {
        val response = followClient.newCall(
            Request.Builder().url(CAS_LOGIN_ENTRY_URL).header("User-Agent", USER_AGENT).get().build()
        ).execute()
        val landed = response.request.url
        response.close()
        Log.d(TAG, "CAS兑票落地: $landed")
        if (landed.host != PORTAL_HOST) return false
        followClient.newCall(
            Request.Builder().url(PORTAL_HOME_URL).header("User-Agent", USER_AGENT).get().build()
        ).execute().close()
        return true
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
            .header("User-Agent", USER_AGENT)
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
