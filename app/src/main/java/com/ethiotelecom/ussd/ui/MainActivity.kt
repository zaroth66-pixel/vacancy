package com.ethiotelecom.ussd.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ethiotelecom.ussd.R
import com.ethiotelecom.ussd.UssdApplication
import com.ethiotelecom.ussd.accessibility.UssdAccessibilityService
import com.ethiotelecom.ussd.databinding.ActivityMainBinding
import com.ethiotelecom.ussd.model.UssdCode
import com.ethiotelecom.ussd.utils.UssdDialer
import com.google.gson.JsonParser

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var codeAdapter: UssdCodeAdapter
    private lateinit var searchAdapter: UssdCodeAdapter

    private val prefs get() = (application as UssdApplication).preferenceManager

    // ── Session polling ───────────────────────────────────────────────────────
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var sessionResultConsumedAt = 0L

    // ── Phone permission ──────────────────────────────────────────────────────
    private val requestPhonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Phone permission required", Toast.LENGTH_LONG).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ensurePhonePermission()
        setupStartButton()
        setupAdapters()
        setupSearch()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    // ── START button ──────────────────────────────────────────────────────────

    private fun setupStartButton() {
        refreshFlowLabel()

        binding.btnStartAutoFlow.setOnClickListener {
            if (!isAccessibilityEnabled()) { promptAccessibility(); return@setOnClickListener }

            val service = UssdAccessibilityService.instance ?: run {
                Toast.makeText(this, "Accessibility service not running — enable in Settings", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val trigger = resolveTriggerCode()
            val started = service.startAutoSession()
            if (!started) {
                Toast.makeText(this, "No auto-flow config — check config JSON", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            setSessionRunning(true)
            binding.tvSessionStatus.text = "Dialling $trigger…"
            binding.tvSessionStatus.visibility = View.VISIBLE

            val result = UssdDialer.dial(this, trigger, null)
            if (!result.success) {
                service.stopSession()
                setSessionRunning(false)
                Toast.makeText(this, result.message ?: "Dial failed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStopAutoFlow.setOnClickListener {
            UssdAccessibilityService.instance?.stopSession()
            setSessionRunning(false)
            binding.tvSessionStatus.text = "Session stopped."
        }
    }

    private fun resolveTriggerCode(): String {
        return try {
            val json = prefs.cachedConfigJson ?: return "*999#"
            val obj  = JsonParser.parseString(json).asJsonObject
            when {
                obj.has("autoFlow") -> obj.getAsJsonObject("autoFlow").get("triggerCode")?.asString ?: "*999#"
                obj.has("defaultUssdCode") -> obj.get("defaultUssdCode").asString
                else -> "*999#"
            }
        } catch (_: Exception) { "*999#" }
    }

    private fun refreshFlowLabel() {
        try {
            val json = prefs.cachedConfigJson ?: run {
                binding.tvFlowDescription.text = "Auto-flow: *999# (load config to set steps)"
                return
            }
            val obj   = JsonParser.parseString(json).asJsonObject
            val steps = when {
                obj.has("autoFlow") -> obj.getAsJsonObject("autoFlow").getAsJsonArray("steps")?.size() ?: 0
                obj.has("rules")    -> obj.getAsJsonArray("rules")?.size() ?: 0
                else -> 0
            }
            binding.tvFlowDescription.text = "Auto-flow: ${resolveTriggerCode()} → $steps steps configured"
        } catch (_: Exception) {}
    }

    private fun setSessionRunning(running: Boolean) {
        binding.btnStartAutoFlow.isEnabled = !running
        binding.btnStopAutoFlow.visibility = if (running) View.VISIBLE else View.GONE
        binding.progressSession.visibility = if (running) View.VISIBLE else View.GONE
    }

    // ── Session polling ───────────────────────────────────────────────────────

    private fun startPolling() {
        val r = object : Runnable {
            override fun run() {
                checkSessionResult()
                pollHandler.postDelayed(this, 500L)
            }
        }
        pollRunnable = r
        pollHandler.post(r)
    }

    private fun stopPolling() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun checkSessionResult() {
        val resultTime = prefs.lastUssdResultTime
        if (resultTime > sessionResultConsumedAt) {
            sessionResultConsumedAt = resultTime
            val text    = prefs.lastUssdResult ?: ""
            val isError = prefs.lastUssdResultIsError
            setSessionRunning(false)
            binding.tvSessionStatus.visibility = View.VISIBLE
            binding.tvSessionStatus.text = if (isError)
                "⚠ Error: ${text.take(100)}"
            else
                "✓ Done: ${text.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: "Complete"}"
        } else {
            // Live screen update
            val screenText = prefs.lastUssdScreenText
            if (!screenText.isNullOrBlank() && binding.progressSession.isShown) {
                val preview = screenText.lines().firstOrNull { it.isNotBlank() } ?: ""
                binding.tvSessionStatus.text = "↪ \"${preview.take(60)}\""
                binding.tvSessionStatus.visibility = View.VISIBLE
            }
        }
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        categoryAdapter = CategoryAdapter { category ->
            codeAdapter.submitList(category.codes)
            binding.rvCodes.visibility     = View.VISIBLE
            binding.rvSearch.visibility    = View.GONE
            binding.tvCategoryTitle.text   = category.name
            binding.tvCategoryTitle.visibility = View.VISIBLE
        }

        codeAdapter   = UssdCodeAdapter(
            onDial = { dialOrPrompt(it) },
            onPin  = { viewModel.togglePin(it) },
            onInfo = { showInfo(it) }
        )
        searchAdapter = UssdCodeAdapter(
            onDial = { dialOrPrompt(it) },
            onPin  = { viewModel.togglePin(it) },
            onInfo = { showInfo(it) }
        )

        binding.rvCategories.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@MainActivity,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
            )
            adapter = categoryAdapter
        }
        binding.rvCodes.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = codeAdapter
        }
        binding.rvSearch.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                viewModel.search(q)
                if (q.isNotEmpty()) {
                    binding.rvSearch.visibility     = View.VISIBLE
                    binding.rvCodes.visibility      = View.GONE
                    binding.rvCategories.visibility = View.GONE
                    binding.tvCategoryTitle.visibility = View.GONE
                } else {
                    binding.rvSearch.visibility     = View.GONE
                    binding.rvCategories.visibility = View.VISIBLE
                }
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text?.clear() }
    }

    // ── ViewModel observers ───────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    binding.progressBar.visibility   = View.VISIBLE
                    binding.layoutContent.visibility = View.GONE
                }
                is UiState.Success -> {
                    binding.progressBar.visibility   = View.GONE
                    binding.layoutContent.visibility = View.VISIBLE
                    categoryAdapter.submitList(state.config.categories)
                    refreshFlowLabel()
                }
                is UiState.Error -> {
                    binding.progressBar.visibility   = View.GONE
                    binding.layoutContent.visibility = View.VISIBLE
                    state.config?.let { categoryAdapter.submitList(it.categories) }
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                    refreshFlowLabel()
                }
            }
        }

        viewModel.filteredCodes.observe(this) { codes ->
            searchAdapter.submitList(codes)
            binding.tvSearchEmpty.visibility =
                if (codes.isEmpty() && binding.etSearch.text?.isNotEmpty() == true)
                    View.VISIBLE else View.GONE
        }

        viewModel.pinnedCodes.observe(this) { pinned ->
            binding.layoutPinned.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ── Dialling ──────────────────────────────────────────────────────────────

    private fun dialOrPrompt(code: UssdCode) {
        if (code.requiresInput) showInputDialog(code) else dial(code, null)
    }

    private fun showInputDialog(code: UssdCode) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
        val et  = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogInput)
        val til = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilDialogInput)
        til.hint = code.inputPrompt ?: "Enter value"
        et.hint  = code.inputHint

        AlertDialog.Builder(this)
            .setTitle(code.label)
            .setView(dialogView)
            .setPositiveButton("Dial") { _, _ ->
                val input = et.text?.toString()
                if (!input.isNullOrBlank()) dial(code, input)
                else Toast.makeText(this, "Input required", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dial(code: UssdCode, input: String?) {
        val result = UssdDialer.dial(this, code.code, input)
        if (result.success) viewModel.recordDial(code)
        else Toast.makeText(this, result.message ?: "Dial failed", Toast.LENGTH_SHORT).show()
    }

    private fun showInfo(code: UssdCode) {
        AlertDialog.Builder(this)
            .setTitle(code.label)
            .setMessage("Code: ${UssdDialer.normalizeForDisplay(code.code)}\nCategory: ${code.category}\n\n${code.description ?: ""}")
            .setPositiveButton("Dial") { _, _ -> dialOrPrompt(code) }
            .setNeutralButton("Close", null)
            .show()
    }

    // ── Accessibility ─────────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any {
            it.contains(packageName, ignoreCase = true)
        }
    }

    private fun promptAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage("Go to Settings → Accessibility → Installed Services → enable '${getString(R.string.app_name)}'")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensurePhonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) requestPhonePermission.launch(Manifest.permission.CALL_PHONE)
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh  -> { viewModel.loadConfig(forceRefresh = true); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else                 -> super.onOptionsItemSelected(item)
    }
}
