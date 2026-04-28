package com.neboer.ecode

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class CredentialManager(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "ecode_cred",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun hasCredential(): Boolean = getUsername() != null

    fun saveXSRFToken(token: String) {
        prefs.edit().putString(KEY_XSRF_TOKEN, token).apply()
    }

    fun getXSRFToken(): String? = prefs.getString(KEY_XSRF_TOKEN, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_XSRF_TOKEN = "xsrf_token"
    }
}
