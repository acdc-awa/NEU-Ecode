package com.neboer.ecode

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import android.util.Log
import java.io.File

/**
 * APK 安装:通过 FileProvider 暴露更新包,拉起系统安装器。
 * Android 8+ 需要用户先授予"安装未知应用"权限,跳系统设置页授权后,
 * 回到 App 由 SettingsActivity.onResume 调用 [resumePendingInstall] 续上安装。
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** 待安装的 APK 路径(等待用户授权期间暂存) */
    @Volatile
    var pendingApkPath: String? = null
        private set

    fun install(activity: Activity, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Log.i(TAG, "缺少安装未知应用权限,跳转授权页")
            pendingApkPath = apkFile.absolutePath
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
            )
            return false
        }
        return launchInstall(activity, apkFile)
    }

    /** 在 Activity.onResume 里调用:授权回来后自动续上安装 */
    fun resumePendingInstall(activity: Activity) {
        val path = pendingApkPath ?: return
        pendingApkPath = null
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "待安装 APK 已不存在: $path")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Log.i(TAG, "用户未授予安装权限,放弃本次安装")
            return
        }
        launchInstall(activity, file)
    }

    private fun launchInstall(activity: Activity, apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "拉起安装器失败: ${e.message}")
            false
        }
    }
}
