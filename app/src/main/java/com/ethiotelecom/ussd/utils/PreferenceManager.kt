package com.ethiotelecom.ussd.utils

import android.content.Context
import android.content.SharedPreferences
import com.ethiotelecom.ussd.model.UssdCode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ussd_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Config ────────────────────────────────────────────────────────────────

    var configUrl: String
        get() = prefs.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL) ?: DEFAULT_CONFIG_URL
        set(v) = prefs.edit().putString(KEY_CONFIG_URL, v).apply()

    var cachedConfigJson: String?
        get() = prefs.getString(KEY_CACHED_CONFIG, null)
        set(v) = prefs.edit().putString(KEY_CACHED_CONFIG, v).apply()

    var lastConfigFetch: Long
        get() = prefs.getLong(KEY_LAST_FETCH, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_FETCH, v).apply()

    // ── Pins ──────────────────────────────────────────────────────────────────

    fun savePinnedCodes(codes: List<UssdCode>) {
        val ids = codes.map { it.id }.toSet()
        prefs.edit().putStringSet(KEY_PINNED_IDS, ids).apply()
    }

    fun getPinnedCodeIds(): Set<String> =
        prefs.getStringSet(KEY_PINNED_IDS, emptySet()) ?: emptySet()

    // ── Use counts ────────────────────────────────────────────────────────────

    fun incrementUseCount(id: String) {
        val cur = getUseCount(id)
        prefs.edit().putInt("use_$id", cur + 1).apply()
    }

    fun getUseCount(id: String): Int = prefs.getInt("use_$id", 0)

    fun setLastUsed(id: String, ts: Long) = prefs.edit().putLong("lu_$id", ts).apply()
    fun getLastUsed(id: String): Long     = prefs.getLong("lu_$id", 0L)

    // ── Recents ───────────────────────────────────────────────────────────────

    fun saveRecentCodes(codes: List<UssdCode>) {
        val json = gson.toJson(codes.map { it.id })
        prefs.edit().putString(KEY_RECENTS, json).apply()
    }

    fun getRecentCodes(): List<UssdCode> {
        // Returns empty — caller reconstructs from config
        return emptyList()
    }

    // ── Session IPC (service → activity) ─────────────────────────────────────

    var lastUssdScreenText: String?
        get() = prefs.getString(KEY_SCREEN_TEXT, null)
        set(v) = prefs.edit().putString(KEY_SCREEN_TEXT, v).apply()

    var lastUssdScreenTime: Long
        get() = prefs.getLong(KEY_SCREEN_TIME, 0L)
        set(v) = prefs.edit().putLong(KEY_SCREEN_TIME, v).apply()

    var lastUssdResult: String?
        get() = prefs.getString(KEY_RESULT_TEXT, null)
        set(v) = prefs.edit().putString(KEY_RESULT_TEXT, v).apply()

    var lastUssdResultIsError: Boolean
        get() = prefs.getBoolean(KEY_RESULT_ERROR, false)
        set(v) = prefs.edit().putBoolean(KEY_RESULT_ERROR, v).apply()

    var lastUssdResultTime: Long
        get() = prefs.getLong(KEY_RESULT_TIME, 0L)
        set(v) = prefs.edit().putLong(KEY_RESULT_TIME, v).apply()

    companion object {
        private const val KEY_CONFIG_URL    = "config_url"
        private const val KEY_CACHED_CONFIG = "cached_config"
        private const val KEY_LAST_FETCH    = "last_fetch"
        private const val KEY_PINNED_IDS    = "pinned_ids"
        private const val KEY_RECENTS       = "recents"
        private const val KEY_SCREEN_TEXT   = "last_screen_text"
        private const val KEY_SCREEN_TIME   = "last_screen_time"
        private const val KEY_RESULT_TEXT   = "last_result_text"
        private const val KEY_RESULT_ERROR  = "last_result_error"
        private const val KEY_RESULT_TIME   = "last_result_time"
        private const val DEFAULT_CONFIG_URL = "https://example.com/ussd/config.json"
    }
}
