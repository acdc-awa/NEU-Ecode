package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var qrBrightness: Int
        get() = prefs.getInt(KEY_QR_BRIGHTNESS, 100)
        set(value) = prefs.edit().putInt(KEY_QR_BRIGHTNESS, value.coerceIn(0, 100)).apply()

    var backPressMode: String
        get() = prefs.getString(KEY_BACK_PRESS_MODE, "double") ?: "double"
        set(value) = prefs.edit().putString(KEY_BACK_PRESS_MODE, value).apply()

    var qrVisible: Boolean
        get() = prefs.getBoolean(KEY_QR_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_QR_VISIBLE, value).apply()

    companion object {
        private const val PREF_NAME = "ecode_settings"
        private const val KEY_QR_BRIGHTNESS = "qr_brightness"
        private const val KEY_BACK_PRESS_MODE = "back_press_mode"
        private const val KEY_QR_VISIBLE = "qr_visible"
    }
}
