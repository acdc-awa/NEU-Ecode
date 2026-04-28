package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences

enum class BackPressMode { SINGLE, DOUBLE }

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

    companion object {
        private const val PREF_NAME = "ecode_settings"
        private const val KEY_BACK_PRESS_MODE = "back_press_mode"
        private const val KEY_QR_VISIBLE = "qr_visible"
    }
}
