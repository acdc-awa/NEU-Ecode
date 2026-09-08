package com.neboer.ecode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * APK 更新包下载器:
 * - 依次尝试代理源与直连,某个源中途失败会换下一个源并带着已下载字节继续
 * - 通过 HTTP Range 实现断点续传,.part 临时文件持久在应用外部私有目录,
 *   App 重启后再次下载同一版本会自动接着下
 * - 进度回调统一投递到主线程,做了节流(约 150ms 一次)
 *
 * 下载必须由调用方在自己的 IO 协程里执行(本类方法阻塞),离开设置页时
 * 随 Activity 生命周期取消,已下载部分保留,下次"继续下载"。
 */
class ApkUpdateDownloader(private val context: Context) {

    interface Listener {
        /** bytes: 已下载总字节, total: 总字节(未知时 -1) */
        fun onProgress(bytes: Long, total: Long)

        fun onCompleted(apkFile: File)

        /** 失败后 .part 保留,再次调用 download 即断点续传 */
        fun onFailed(message: String)
    }

    companion object {
        private const val TAG = "ApkUpdateDownloader"
        private const val UPDATE_DIR = "update"
        private const val PART_SUFFIX = ".part"
        private const val PROGRESS_INTERVAL_MS = 150L

        fun updateDir(context: Context): File =
            File(context.getExternalFilesDir(null), UPDATE_DIR).apply { mkdirs() }

        /** 正在下载的临时文件 */
        fun partialFile(context: Context, versionName: String): File =
            File(updateDir(context), "Ecode-$versionName.apk$PART_SUFFIX")

        /** 下载完成的安装包 */
        fun downloadedApk(context: Context, versionName: String): File =
            File(updateDir(context), "Ecode-$versionName.apk")

        /**
         * 清理不属于指定版本的残留安装包/临时文件(新版本发布后旧文件就没用了)。
         * 传入 null 时清理全部。
         */
        fun cleanupExcept(context: Context, versionName: String?) {
            val files = updateDir(context).listFiles() ?: return
            for (f in files) {
                val keep = versionName != null &&
                    (f.name == "Ecode-$versionName.apk" || f.name == "Ecode-$versionName.apk$PART_SUFFIX")
                if (!keep && f.name.startsWith("Ecode-")) {
                    f.delete()
                    Log.i(TAG, "清理旧更新包: ${f.name}")
                }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeCall: okhttp3.Call? = null

    @Volatile
    private var cancelled = false

    /** 阻塞下载,直到完成、失败或被 cancel()。在 IO 线程调用。 */
    fun download(release: ReleaseInfo, listener: Listener) {
        cancelled = false
        val partFile = partialFile(context, release.versionName)
        // URL 拼接代理前缀即可加速,直连("")永远兜底
        val sources = UpdateChecker.PROXY_PREFIXES + ""
        var lastError: String = "未知错误"

        for (source in sources) {
            if (cancelled) return
            try {
                val done = downloadFrom(source, release, partFile, listener)
                if (done) return
            } catch (e: Exception) {
                if (cancelled) return
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "源 $source 下载失败: $lastError,换下一个源继续")
            }
        }
        if (cancelled) return
        Log.e(TAG, "所有源下载失败: $lastError")
        mainHandler.post { listener.onFailed(lastError) }
    }

    /** 暂停/放弃当前下载,已下载部分保留可续传 */
    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    /** 单源下载,返回 true 表示完成;抛异常表示该源失败(换源续传) */
    private fun downloadFrom(
        sourcePrefix: String,
        release: ReleaseInfo,
        partFile: File,
        listener: Listener,
    ): Boolean {
        val resumedBytes = if (partFile.exists()) partFile.length() else 0L
        val request = Request.Builder()
            .url(sourcePrefix + release.apkUrl)
            .header("Range", "bytes=$resumedBytes-")
            // 避免 OkHttp 透明 gzip 干扰 content-length/进度计算
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { resp ->
            when {
                // 206 Partial Content:服务器支持断点,直接接着写
                resp.code == 206 -> Unit
                // 服务器不支持 Range,忽略已下载部分从头来
                resp.code == 200 -> {
                    if (resumedBytes > 0L) {
                        Log.i(TAG, "源不支持断点续传(HTTP 200),从头下载")
                        partFile.delete()
                    }
                    partFile.parentFile?.mkdirs()
                    partFile.createNewFile()
                }
                // 416 Range Not Satisfiable:本地文件大小不匹配远端,放弃续传从头下
                resp.code == 416 -> {
                    Log.w(TAG, "续传起点不被接受(HTTP 416),删除 .part 从头下载")
                    partFile.delete()
                    throw IOException("续传失效,重试将重新下载")
                }
                else -> throw IOException("HTTP ${resp.code}")
            }

            val body = resp.body ?: throw IOException("响应体为空")
            // 总大小:206 从 Content-Range 尾段取,200 从 Content-Length 取
            val total = if (resp.code == 206) {
                resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            } else {
                body.contentLength().takeIf { it >= 0 } ?: -1L
            }

            var bytesDone = if (resp.code == 206) resumedBytes else 0L
            var lastReportAt = 0L
            val buf = ByteArray(64 * 1024)
            // 206 追加写(断点续传),200 覆盖写
            val append = resp.code == 206
            body.byteStream().use { input ->
                BufferedOutputStream(
                    java.io.FileOutputStream(partFile, append), buf.size
                ).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        bytesDone += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= PROGRESS_INTERVAL_MS) {
                            lastReportAt = now
                            val done = bytesDone
                            mainHandler.post { listener.onProgress(done, total) }
                        }
                    }
                }
            }
            // 流提前结束但没到总大小:连接被截断,视为失败(可换源/续传)
            if (total > 0 && bytesDone < total) {
                throw IOException("连接中断($bytesDone/$total)")
            }

            // 大小校验通过,落为正式 APK 文件
            val target = downloadedApk(context, release.versionName)
            if (target.exists()) target.delete()
            if (!partFile.renameTo(target)) {
                throw IOException("文件重命名失败")
            }
            mainHandler.post { listener.onCompleted(target) }
            Log.i(TAG, "下载完成: ${target.name} ($bytesDone 字节)")
            return true
        }
    }
}
