package com.neboer.ecode

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val settings = AppSettings(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val radioSingle: MaterialRadioButton = findViewById(R.id.radioSingle)
        val radioDouble: MaterialRadioButton = findViewById(R.id.radioDouble)
        radioSingle.isChecked = settings.backPressMode == BackPressMode.SINGLE
        radioDouble.isChecked = settings.backPressMode == BackPressMode.DOUBLE

        findViewById<RadioGroup>(R.id.radioGroupBackMode).setOnCheckedChangeListener { _, id ->
            settings.backPressMode = when (id) {
                R.id.radioSingle -> BackPressMode.SINGLE
                else -> BackPressMode.DOUBLE
            }
        }

        findViewById<MaterialButton>(R.id.btnSwitchAccount).setOnClickListener {
            PersistentCookieJar(this).clear()
            CredentialManager(this).clear()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.settings_version, versionName)
    }
}
