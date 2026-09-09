package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences

enum class BackPressMode { SINGLE, DOUBLE }

/** 余额数据源:ECARD=一卡通自助查询(权威值,仅校园网);PORTAL=门户个人数据 JSON(公网可达,数值可能不同步) */
enum class BalanceSourceKind { ECARD, PORTAL }

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var backPressMode: BackPressMode
        get() = when (prefs.getString(KEY_BACK_PRESS_MODE, "double")) {
            "single" -> BackPressMode.SINGLE
            else -> BackPressMode.DOUBLE
        }
        set(value) = prefs.edit().putString(KEY_BACK_PRESS_MODE, value.name.lowercase()).apply()

    var qrVisible: Boolean
        get() = prefs.getBoolean(KEY_QR_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_QR_VISIBLE, value).apply()

    /** 更新下载源,取值见 UpdateChecker.UI_SOURCES 的 id,默认自动测速 */
    var updateSourceId: String
        get() = prefs.getString(KEY_UPDATE_SOURCE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_UPDATE_SOURCE, value).apply()

    /** 余额数据源,默认 ecard */
    var balanceSource: BalanceSourceKind
        get() = when (prefs.getString(KEY_BALANCE_SOURCE, null)) {
            "portal" -> BalanceSourceKind.PORTAL
            else -> BalanceSourceKind.ECARD
        }
        set(value) = prefs.edit().putString(KEY_BALANCE_SOURCE, value.name.lowercase()).apply()

    companion object {
        private const val PREF_NAME = "ecode_settings"
        private const val KEY_BACK_PRESS_MODE = "back_press_mode"
        private const val KEY_QR_VISIBLE = "qr_visible"
        private const val KEY_UPDATE_SOURCE = "update_source"
        private const val KEY_BALANCE_SOURCE = "balance_source"
    }
}
