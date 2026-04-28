package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray

class PersistentCookieJar(context: Context) : CookieJar {

    companion object {
        private const val TAG = "CookieJar"
        private const val PREF_NAME = "ecode_cookies"
        private const val KEY_COOKIES = "cookies"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val cache = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        Log.d(TAG, "save: url=$url, new=${cookies.size}, total=${cache.size}")
        cookies.forEach { c ->
            Log.d(TAG, "  + ${c.name} domain=${c.domain} path=${c.path}")
            cache.removeAll { it.name == c.name && it.domain == c.domain }
        }
        // 统一 path 为 "/"，避免 cookie 因 path 限制无法在子路径间共享
        cache.addAll(cookies.map { c ->
            if (c.path == "/") c
            else Cookie.Builder().domain(c.domain).path("/").name(c.name).value(c.value)
                .also { if (c.secure) it.secure() }
                .also { if (c.httpOnly) it.httpOnly() }
                .build()
        })
        val json = JSONArray()
        cache.forEach { c ->
            json.put(org.json.JSONObject().apply {
                put("name", c.name)
                put("value", c.value)
                put("domain", c.domain)
                put("path", c.path)
            })
        }
        prefs.edit().putString(KEY_COOKIES, json.toString()).apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (cache.isEmpty()) {
            val raw = prefs.getString(KEY_COOKIES, null) ?: return emptyList()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val o = jsonArray.getJSONObject(i)
                cache.add(Cookie.Builder()
                    .domain(o.getString("domain"))
                    .path(o.getString("path"))
                    .name(o.getString("name"))
                    .value(o.getString("value"))
                    .build())
            }
        }
        val matched = cache.filter { it.matches(url) }
        Log.d(TAG, "load: matched=${matched.size}/${cache.size} for ${url.encodedPath}")
        cache.forEach { c ->
            val m = c.matches(url)
            Log.d(TAG, "  ${if (m) "✓" else "✗"} ${c.name} domain=${c.domain} path=${c.path}")
        }
        return matched
    }

    fun clear() {
        Log.d(TAG, "clear: 清除所有cookies")
        cache.clear()
        prefs.edit().remove(KEY_COOKIES).apply()
    }
}
