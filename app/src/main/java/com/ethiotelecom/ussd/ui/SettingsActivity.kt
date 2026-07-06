package com.ethiotelecom.ussd.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ethiotelecom.ussd.UssdApplication
import com.ethiotelecom.ussd.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs  by lazy { (application as UssdApplication).preferenceManager }
    private val repo   by lazy { (application as UssdApplication).configRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.etConfigUrl.setText(prefs.configUrl)

        // Save URL
        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etConfigUrl.text?.toString()?.trim()
            if (!url.isNullOrEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                prefs.configUrl = url
                Toast.makeText(this, "URL saved", Toast.LENGTH_SHORT).show()
            } else {
                binding.tilConfigUrl.error = "Enter a valid URL"
            }
        }

        // Reset URL
        binding.btnResetUrl.setOnClickListener {
            prefs.configUrl = "https://example.com/ussd/config.json"
            binding.etConfigUrl.setText(prefs.configUrl)
            Toast.makeText(this, "Reset to default", Toast.LENGTH_SHORT).show()
        }

        // Manual JSON paste — inject directly without network
        binding.btnInjectJson.setOnClickListener {
            val json = binding.etManualJson.text?.toString()?.trim()
            if (json.isNullOrBlank()) {
                Toast.makeText(this, "Paste your config JSON first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = repo.injectConfigJson(json)
            if (result.isSuccess) {
                Toast.makeText(this, "Config loaded — ${result.getOrThrow().categories.size} categories", Toast.LENGTH_LONG).show()
                binding.etManualJson.text?.clear()
            } else {
                Toast.makeText(this, "Invalid JSON: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Show cached JSON
        binding.btnShowCached.setOnClickListener {
            val json = prefs.cachedConfigJson
            if (json != null) {
                binding.etManualJson.setText(json)
            } else {
                Toast.makeText(this, "No cached config", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
