package com.neboer.ecode.webviewdemo

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 门户(personal.neu.edu.cn)余额链路测试:纯 CASTGC 静默兑票,不带账密。
 * 流程移植自主 app PortalClient(备用数据源,见 .har/personal.neu.edu.cn.har):
 * 1. GET /portal/personal/frontend/data/items?type=personal_data
 *    → {"e":0,"d":{"data":[{"key":"card.balance","id":"<动态哈希>",...}]}}
 * 2. GET /portal/personal/frontend/data/detail?id=<id>
 *    → {"e":0,"m":"操作成功","d":{"data":{"value":"50.33","unit":"元"}}}
 * 会话经 CAS service=https://personal.neu.edu.cn/ 兑票建立,落地后下发 SESS_ID。
 * 注意:门户与 ecard 的余额数值不同步(门户读数偏大),这里仅验证接口可用性。
 */
class PortalTester(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "PortalTester"
        private const val ITEMS_URL =
            "https://personal.neu.edu.cn/portal/personal/frontend/data/items?type=personal_data"
        private const val DETAIL_URL =
            "https://personal.neu.edu.cn/portal/personal/frontend/data/detail"
        private const val CARD_BALANCE_KEY = "card.balance"
        private const val NET_BALANCE_KEY = "net.balance"

        // 门户自己的 CAS 入口(ticket 消费端点是 cas_login/1,service 由服务端自己拼;
        // 直接对 tpass 传 service=根路径 无效——根路径不消费 ticket,详见 .har/personal.neu.edu.cn.har)
        private const val CAS_LOGIN_ENTRY_URL =
            "https://personal.neu.edu.cn/portal/manage/common/cas_login/1?to_bind=0&redirect=https%3A%2F%2Fpersonal.neu.edu.cn%2Fportal"
        // SESS_ID 由 /portal/ 页面签发,兑票成功后需预热一发
        private const val PORTAL_HOME_URL = "https://personal.neu.edu.cn/portal/"
        private const val PORTAL_HOST = "personal.neu.edu.cn"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    private val trace = StringBuilder()

    // API 不跟随重定向:未登录时门户会 302 去 CAS,按无会话处理而不是解析登录页 HTML
    private val apiClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val followClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 主入口:返回给界面展示的文本(含流程轨迹)。不抛异常。 */
    fun fetchBalance(): String {
        trace.clear()
        trace.append("① 现有会话直接请求 items 接口\n")
        var cardId = findItemId(CARD_BALANCE_KEY)
        if (cardId == null) {
            trace.append("② 无会话,走门户 cas_login 入口兑票建立会话(依赖 WebView 拦截到的 CASTGC,不带账密)\n")
            if (!establishPortalSession()) {
                return done("✗ 兑票链未成功落回 personal.neu.edu.cn(CASTGC 未生效或网络环境拦截,见轨迹)")
            }
            trace.append("③ 会话已建立,重新请求 items\n")
            cardId = findItemId(CARD_BALANCE_KEY)
            if (cardId == null) return done("✗ 兑票后仍未从 items 拿到 card.balance 的 id")
        }

        val card = fetchDetail(cardId)
        if (card == null) return done("✗ detail 接口未解析到 card.balance 数值")

        // 同一 items 列表顺手取网费,展示 JSON 接口的可扩展性
        val netId = findItemId(NET_BALANCE_KEY)
        val net = netId?.let { fetchDetail(it) }

        val sb = StringBuilder("【✓ 门户接口打通】\n")
        sb.append("   card.balance(校园卡) = ${card.value} ${card.unit}")
        sb.append("(注:门户与ecard数值不同步,仅验证接口)\n")
        if (netId != null) {
            sb.append("   net.balance(网费) = ${net?.value ?: "?"} ${net?.unit ?: ""}\n")
        }
        return sb.toString() + "\n" + trace
    }

    private class DetailValue(val value: String, val unit: String)

    private fun done(result: String): String = "【$result】\n$trace"

    private fun traceLine(msg: String) {
        Log.d(TAG, msg)
        trace.append("   $msg\n")
    }

    /**
     * 走门户自己的 SSO 入口建立会话:cas_login/1 → 302 tpass/login?service=…(CASTGC 自动过站)
     * → 302 cas_login/1?…&ticket=ST → 校验下发 CK_LC/CK_VL → 落回 /portal。
     * SESS_ID 由 /portal/ 页面签发,兑票成功后补一发预热。
     */
    private fun establishPortalSession(): Boolean {
        val response = followClient.newCall(
            Request.Builder().url(CAS_LOGIN_ENTRY_URL).header("User-Agent", USER_AGENT).get().build()
        ).execute()
        val landed = response.request.url
        val code = response.code
        response.close()
        traceLine("兑票链落地: HTTP $code $landed")
        if (landed.host != PORTAL_HOST) return false

        val home = followClient.newCall(
            Request.Builder().url(PORTAL_HOME_URL).header("User-Agent", USER_AGENT).get().build()
        ).execute()
        home.close()
        traceLine("门户首页预热(签发SESS_ID): $PORTAL_HOME_URL")
        return true
    }

    /** 从 items 列表按 key 找条目 id;未登录(302)或结构变化返回 null */
    private fun findItemId(key: String): String? {
        val body = getJson(ITEMS_URL) ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("e", -1) != 0) {
                traceLine("items接口报错: e=${json.optInt("e")} m=${json.optString("m")}")
                return null
            }
            val items = json.getJSONObject("d").getJSONArray("data")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("key") == key) {
                    val id = item.optString("id")
                    traceLine("items 中 key=$key → id=${id.take(24)}…")
                    return id.takeIf { it.isNotEmpty() }
                }
            }
            traceLine("items中未找到key=$key (共${items.length()}条)")
            null
        } catch (e: Exception) {
            traceLine("items响应解析失败: ${body.take(200)}")
            null
        }
    }

    private fun fetchDetail(id: String): DetailValue? {
        val body = getJson("$DETAIL_URL?id=$id") ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("e", -1) != 0) {
                traceLine("detail接口报错: e=${json.optInt("e")} m=${json.optString("m")}")
                return null
            }
            val data = json.getJSONObject("d").getJSONObject("data")
            val value = data.optString("value")
            if (value.isBlank()) {
                traceLine("detail value 为空")
                null
            } else {
                DetailValue(value, data.optString("unit", "元"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "detail响应解析失败", e)
            traceLine("detail响应解析失败: ${body.take(200)}")
            null
        }
    }

    private fun getJson(url: String): String? {
        return try {
            apiClient.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://personal.neu.edu.cn/portal")
                    .header("X-Requested-With", "XMLRequest")
                    .get().build()
            ).execute().use { resp ->
                val body = resp.body?.string()
                traceLine("GET $url → HTTP ${resp.code}, ${body?.length ?: 0}字")
                if (resp.isSuccessful) body else null
            }
        } catch (e: Exception) {
            traceLine("GET $url 异常: ${e.message}")
            null
        }
    }
}
