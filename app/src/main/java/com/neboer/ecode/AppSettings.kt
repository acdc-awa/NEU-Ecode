package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences

enum class BackPressMode { SINGLE, DOUBLE }

/** 二维码展示时的亮度模式: FULL=全屏最高亮度(默认,传统); HDR=仅二维码HDR局部高亮(实验性); SYSTEM=保持系统亮度 */
enum class QrBrightnessMode { FULL, HDR, SYSTEM }

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var backPressMode: BackPressMode
        get() = when (prefs.getString(KEY_BACK_PRESS_MODE, "double")) {
            "single" -> BackPressMode.SINGLE
            else -> BackPressMode.DOUBLE
        }
        set(value) = prefs.edit().putString(KEY_BACK_PRESS_MODE, value.name.lowercase()).apply()

    /** 打开应用时二维码是否直接显示(会话内点卡片切换不持久化,下次开屏仍按此偏好) */
    var qrShowOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_QR_SHOW_ON_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_QR_SHOW_ON_LAUNCH, value).apply()

    /** 更新下载源,取值见 UpdateChecker.UI_SOURCES 的 id,默认自动测速 */
    var updateSourceId: String
        get() = prefs.getString(KEY_UPDATE_SOURCE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_UPDATE_SOURCE, value).apply()

    /** 二维码展示时的亮度/高亮模式,默认全屏最高亮度(传统);HDR 局部高亮为实验性功能 */
    var qrBrightnessMode: QrBrightnessMode
        get() = when (prefs.getString(KEY_QR_BRIGHTNESS_MODE, "full")) {
            "hdr" -> QrBrightnessMode.HDR
            "system" -> QrBrightnessMode.SYSTEM
            else -> QrBrightnessMode.FULL
        }
        set(value) = prefs.edit().putString(KEY_QR_BRIGHTNESS_MODE, value.name.lowercase()).apply()

    companion object {
        private const val PREF_NAME = "ecode_settings"
        private const val KEY_BACK_PRESS_MODE = "back_press_mode"
        private const val KEY_QR_SHOW_ON_LAUNCH = "qr_show_on_launch"
        private const val KEY_UPDATE_SOURCE = "update_source"
        private const val KEY_QR_BRIGHTNESS_MODE = "qr_brightness_mode"
    }
}
