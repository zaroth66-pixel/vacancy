package com.ethiotelecom.ussd.network

import android.content.Context
import com.ethiotelecom.ussd.model.*
import com.ethiotelecom.ussd.utils.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Config repository.
 *
 * Load priority:
 *  1. SharedPreferences cache (instant, offline)
 *  2. assets/config.json bundled in APK (offline fallback)
 *  3. Remote HTTP fetch (when online and forceRefresh=true or cache stale)
 *  4. Hardcoded fallback (always works)
 *
 * The APK works fully offline after first install.
 * When the phone has internet it optionally refreshes from your server.
 */
class ConfigRepository(
    private val context: Context,
    private val prefs: PreferenceManager
) {
    private val gson           = Gson()
    private val cacheDurationMs = 6 * 60 * 60 * 1000L   // 6 hours

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun getConfig(forceRefresh: Boolean = false): Result<UssdConfig> =
        withContext(Dispatchers.IO) {
            val now         = System.currentTimeMillis()
            val cacheStale  = (now - prefs.lastConfigFetch) > cacheDurationMs

            // 1. Cache hit (fresh)
            if (!forceRefresh && !cacheStale) {
                tryParseCached()?.let { return@withContext Result.success(it) }
            }

            // 2. Asset bundle
            tryLoadAsset()?.let { (config, json) ->
                if (forceRefresh.not()) {
                    prefs.cachedConfigJson = json
                    prefs.lastConfigFetch  = now
                }
                // Still try network silently if refresh requested
            }

            // 3. Network (only if online)
            if (forceRefresh || cacheStale) {
                tryFetchRemote(now)?.let { return@withContext Result.success(it) }
            }

            // 4. Cached (even if stale)
            tryParseCached()?.let { return@withContext Result.success(it) }

            // 5. Asset
            tryLoadAsset()?.let { return@withContext Result.success(it.first) }

            // 6. Hardcoded
            Result.success(getHardcodedConfig())
        }

    // ── Loaders ───────────────────────────────────────────────────────────────

    private fun tryParseCached(): UssdConfig? {
        val json = prefs.cachedConfigJson ?: return null
        return runCatching { gson.fromJson(json, UssdConfig::class.java) }.getOrNull()
    }

    private fun tryLoadAsset(): Pair<UssdConfig, String>? = runCatching {
        val json   = context.assets.open("config.json").bufferedReader().use { it.readText() }
        val config = gson.fromJson(json, UssdConfig::class.java)
        Pair(config, json)
    }.getOrNull()

    private fun tryFetchRemote(now: Long): UssdConfig? = runCatching {
        val req  = Request.Builder()
            .url(prefs.configUrl)
            .addHeader("Accept", "application/json")
            .addHeader("Cache-Control", "no-cache")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        val config = gson.fromJson(body, UssdConfig::class.java)
        prefs.cachedConfigJson = body
        prefs.lastConfigFetch  = now
        config
    }.getOrNull()

    // ── Manual config inject (from Settings paste field) ─────────────────────

    fun injectConfigJson(rawJson: String): Result<UssdConfig> = runCatching {
        val config = gson.fromJson(rawJson, UssdConfig::class.java)
        prefs.cachedConfigJson = rawJson
        prefs.lastConfigFetch  = System.currentTimeMillis()
        config
    }

    // ── AutoFlow loader ───────────────────────────────────────────────────────

    fun loadAutoFlow(): AutoFlow? {
        val json = prefs.cachedConfigJson
            ?: runCatching { context.assets.open("config.json").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return null
        return runCatching {
            val obj  = JsonParser.parseString(json).asJsonObject
            when {
                obj.has("autoFlow") ->
                    gson.fromJson(obj.getAsJsonObject("autoFlow"), AutoFlow::class.java)
                obj.has("rules") -> {
                    val rules   = obj.getAsJsonArray("rules").map { gson.fromJson(it, UssdRule::class.java) }
                    val trigger = obj.get("defaultUssdCode")?.asString ?: "*999#"
                    AutoFlow(triggerCode = trigger, steps = rules)
                }
                else -> null
            }
        }.getOrNull()
    }

    // ── Hardcoded fallback ────────────────────────────────────────────────────

    fun getBundledConfig(): UssdConfig = getHardcodedConfig()

    fun getHardcodedConfig(): UssdConfig = UssdConfig(
        version     = "2.0.0",
        operator    = "Ethio Telecom",
        lastUpdated = System.currentTimeMillis(),
        categories  = listOf(
            UssdCategory("Balance & Data", "💰", listOf(
                UssdCode("bal_main",  "Balance & Data", null, "Main Balance",   "*804#",   "Check airtime balance"),
                UssdCode("bal_data",  "Balance & Data", null, "Data Balance",   "*804*3#", "Check data balance"),
                UssdCode("bal_bonus", "Balance & Data", null, "Bonus Balance",  "*804*4#", "Check bonus credit"),
                UssdCode("bal_sms",   "Balance & Data", null, "SMS Balance",    "*804*6#", "Remaining SMS count"),
                UssdCode("bal_voice", "Balance & Data", null, "Voice Balance",  "*804*5#", "Remaining voice minutes"),
            )),
            UssdCategory("Recharge", "🔋", listOf(
                UssdCode("rch_scratch", "Recharge", null, "Scratch Card", "*805*{INPUT}#", "Recharge via scratch card",
                    requiresInput=true, inputPrompt="Enter PIN", inputHint="16-digit PIN"),
                UssdCode("rch_etopup",  "Recharge", null, "E-Top Up",    "*805*1*{INPUT}#", "Recharge via E-Top Up",
                    requiresInput=true, inputPrompt="Enter amount", inputHint="e.g. 10, 25, 50"),
                UssdCode("rch_transfer","Recharge", null, "Credit Transfer","*809*{INPUT}#","Transfer credit",
                    requiresInput=true, inputPrompt="Number*Amount", inputHint="e.g. 0911234567*50"),
            )),
            UssdCategory("Data Bundles", "📶", listOf(
                UssdCode("data_buy",     "Data Bundles", null,      "Buy Bundle",     "*836#",   "Browse data bundles"),
                UssdCode("data_daily",   "Data Bundles", "Daily",   "Daily Bundle",   "*836*1#", "Daily data"),
                UssdCode("data_weekly",  "Data Bundles", "Weekly",  "Weekly Bundle",  "*836*2#", "Weekly data"),
                UssdCode("data_monthly", "Data Bundles", "Monthly", "Monthly Bundle", "*836*3#", "Monthly data"),
            )),
            UssdCategory("Telebirr", "💳", listOf(
                UssdCode("tele_menu",    "Telebirr", null, "Telebirr Menu",    "*127#",   "Mobile wallet"),
                UssdCode("tele_balance", "Telebirr", null, "Telebirr Balance", "*127*4#", "Wallet balance"),
                UssdCode("tele_send",    "Telebirr", null, "Send Money",       "*127*1#", "Send via Telebirr"),
                UssdCode("tele_withdraw","Telebirr", null, "Withdraw Cash",    "*127*3#", "Withdraw from Telebirr"),
                UssdCode("tele_pay",     "Telebirr", null, "Pay Bill",         "*127*5#", "Pay bills"),
            )),
            UssdCategory("Voice Packages", "📞", listOf(
                UssdCode("voice_pkg",    "Voice Packages", null, "Buy Voice Package",      "*837#",   "Browse voice packages"),
                UssdCode("voice_daily",  "Voice Packages", null, "Daily Voice Package",    "*837*1#", "Daily voice bundle"),
                UssdCode("voice_weekly", "Voice Packages", null, "Weekly Voice Package",   "*837*2#", "Weekly voice bundle"),
                UssdCode("voice_intl",   "Voice Packages", null, "International Package",  "*837*4#", "International minutes"),
            )),
            UssdCategory("Customer Care", "🎧", listOf(
                UssdCode("care_main",      "Customer Care", null, "Customer Care Menu", "*101#",   "Self-service"),
                UssdCode("care_complaint", "Customer Care", null, "Lodge Complaint",    "*101*2#", "Submit complaint"),
                UssdCode("care_faq",       "Customer Care", null, "FAQ & Help",         "*101*1#", "Browse FAQs"),
                UssdCode("care_call",      "Customer Care", null, "Call Customer Care", "994",     "Direct call"),
            )),
        )
    )
}
