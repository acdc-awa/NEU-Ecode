package com.neboer.ecode

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        val credentialManager = CredentialManager(this)
        if (credentialManager.hasCredential()) {
            Log.d(TAG, "已有存储凭据，跳过登录 → MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        Log.d(TAG, "无存储凭据，显示登录页")
        setContentView(R.layout.activity_login)

        val etUsername = findViewById<TextInputEditText>(R.id.etUsername)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val tvError = findViewById<TextView>(R.id.tvLoginError)

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text?.toString()?.trim() ?: ""
            Log.d(TAG, "点击登录: user=$username")
            if (username.isEmpty() || password.isEmpty()) {
                Log.d(TAG, "账密为空，拒绝提交")
                tvError.text = "学号和密码不能为空"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            tvError.visibility = View.GONE
            btnLogin.isEnabled = false
            btnLogin.text = "登录中..."

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    Log.d(TAG, "开始CAS认证...")
                    val okHttpClient = okhttp3.OkHttpClient.Builder()
                        .cookieJar(PersistentCookieJar(this@LoginActivity))
                        .build()
                    val casAuth = CasAuthenticator(okHttpClient, credentialManager)
                    val result = casAuth.login(username, password)
                    Log.d(TAG, "CAS认证结果: $result")
                    result
                }

                if (success) {
                    Log.d(TAG, "登录成功，保存凭据")
                    credentialManager.save(username, password)
                    Log.d(TAG, "登录成功，跳转MainActivity")
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Log.w(TAG, "登录失败，恢复UI")
                    btnLogin.isEnabled = true
                    btnLogin.text = "登录"
                    tvError.text = "登录失败，请检查学号和密码"
                    tvError.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onBackPressed() {
        finishAffinity()
    }
}
