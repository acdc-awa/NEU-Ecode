package com.neboer.ecode

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub Release 上可下载的最新版本信息 */
data class ReleaseInfo(
    val tagName: String, // 原始 tag,形如 "v1.2.3"
    val versionName: String, // 去掉 v 前缀的 "1.2.3",与 versionName 对齐
    val apkName: String, // 资产文件名,如 "Ecode-1.2.3.apk"
    val apkUrl: String, // github.com 原始下载地址(下载时按需拼接代理前缀)
    val apkSize: Long, // APK 字节数
    val notes: String?, // Release 说明(markdown 原文)
)

sealed class UpdateCheckResult {
    /** 已是最新(或本地比线上还新,如本地 dev 构建) */
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()

    data class Available(val release: ReleaseInfo) : UpdateCheckResult()
}

/**
 * 在线更新检查:查询 GitHub 最新 release 并比较版本号。
 *
 * 版本号约定为 v<xx.xx.xx>(见 app/build.gradle.kts 的 tag 推导),
 * 比较时按 major/minor/patch 三段数字逐段比较;当前版本在本地 dev 构建
 * 下可能是 "1.2.3-3-gabc" 这类 git describe 后缀,解析时忽略后缀部分。
 */
class UpdateChecker {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val REPO = "acdc-awa/NEU-Ecode"
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /**
         * GitHub 加速代理前缀(实测支持 Range 断点续传),拼接在完整 URL 之前,
         * 形如 "https://gh.llkk.cc/https://github.com/..."。
         * 这类镜像站可用性会随时间变化,失效时增删此列表即可;直连始终作为兜底。
         */
        val PROXY_PREFIXES = listOf(
            "https://gh.llkk.cc/",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
        )

        /**
         * 解析 "v1.2.3" / "1.2.3" / "1.2.3-3-gabc"(本地 git describe 产物)为
         * (major, minor, patch)。允许省略后两段(v1.2 视为 v1.2.0),
         * 允许 -rc1 / +build 之类后缀(仅精确 tag 比较时忽略)。
         */
        fun parseVersion(name: String): Triple<Int, Int, Int>? {
            val m = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""")
                .find(name.trim()) ?: return null
            return Triple(
                m.groupValues[1].toInt(),
                m.groupValues[2].ifEmpty { "0" }.toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
            )
        }

        /** 三段数字逐段比较;相等不算新版本 */
        fun isNewer(latestVersionName: String, currentVersionName: String): Boolean {
            val latest = parseVersion(latestVersionName) ?: return false
            val current = parseVersion(currentVersionName) ?: return true // 当前版本无法识别时允许更新
            return toVersionCode(latest) > toVersionCode(current)
        }

        /** (major, minor, patch) → 可比较的单值,与 versionCode 的推导规则一致 */
        private fun toVersionCode(v: Triple<Int, Int, Int>): Long =
            v.first * 1_000_000L + v.second * 1_000L + v.third
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 查询最新 release。api.github.com 直连失败时依次尝试代理前缀。
     * 网络上全部失败时抛 IOException,由调用方提示。
     */
    fun check(currentVersionName: String): UpdateCheckResult {
        val release = fetchLatestRelease()
            ?: throw IOException("所有源均无法访问 GitHub")
        // tag 不符合 v<xx.xx.xx> 约定或没有 APK 资产,视为检查失败而不是误报更新
        val latest = parseVersion(release.tagName)
            ?: throw IOException("最新版本号 ${release.tagName} 无法识别")
        val current = parseVersion(currentVersionName)
        Log.i(
            TAG, "版本比较: 线上 ${release.tagName}=$latest vs 本地 $currentVersionName=$current"
        )
        return if (current == null || isNewer(release.versionName, currentVersionName)) {
            UpdateCheckResult.Available(release)
        } else {
            UpdateCheckResult.UpToDate(currentVersionName)
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        // 直连优先(部分网络环境下最快),失败后走代理
        val candidates = listOf("") + PROXY_PREFIXES
        for (prefix in candidates) {
            val url = prefix + API_URL
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "NEU-Ecode-App")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "查询 release 失败: ${resp.code} ($url)")
                        return@use
                    }
                    val body = resp.body?.string() ?: return@use
                    return parseRelease(body)
                }
            } catch (e: IOException) {
                Log.w(TAG, "查询 release 网络异常 ($url): ${e.message}")
            }
        }
        return null
    }

    /** 从 releases/latest JSON 里取第一个 .apk 资产 */
    private fun parseRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name")
            val assets = obj.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return ReleaseInfo(
                        tagName = tagName,
                        versionName = tagName.removePrefix("v"),
                        apkName = name,
                        apkUrl = asset.optString("browser_download_url"),
                        apkSize = asset.optLong("size", -1L),
                        notes = obj.optString("body").takeIf { it.isNotBlank() },
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "解析 release JSON 失败: ${e.message}")
            null
        }
    }
}
