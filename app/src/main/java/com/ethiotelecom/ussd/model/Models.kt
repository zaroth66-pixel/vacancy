package com.ethiotelecom.ussd.model

import com.google.gson.annotations.SerializedName

// ── USSD Code catalogue models ────────────────────────────────────────────────

data class UssdCode(
    @SerializedName("id")           val id: String,
    @SerializedName("category")     val category: String,
    @SerializedName("subcategory")  val subcategory: String? = null,
    @SerializedName("label")        val label: String,
    @SerializedName("code")         val code: String,
    @SerializedName("description")  val description: String? = null,
    @SerializedName("requiresInput")val requiresInput: Boolean = false,
    @SerializedName("inputPrompt")  val inputPrompt: String? = null,
    @SerializedName("inputHint")    val inputHint: String? = null,
    @SerializedName("tags")         val tags: List<String> = emptyList(),
    @SerializedName("isPinned")     var isPinned: Boolean = false,
    @SerializedName("lastUsed")     var lastUsed: Long = 0L,
    @SerializedName("useCount")     var useCount: Int = 0
)

data class UssdCategory(
    @SerializedName("name")  val name: String,
    @SerializedName("icon")  val icon: String,
    @SerializedName("codes") val codes: List<UssdCode>
)

data class UssdConfig(
    @SerializedName("version")     val version: String,
    @SerializedName("operator")    val operator: String,
    @SerializedName("categories")  val categories: List<UssdCategory>,
    @SerializedName("lastUpdated") val lastUpdated: Long
)

data class DialResult(
    val success: Boolean,
    val code: String,
    val message: String? = null
)

// ── Auto-flow / Rule engine models ────────────────────────────────────────────

/**
 * One step in an automated USSD navigation flow.
 *
 * Server JSON shape:
 * {
 *   "id": "step_0",
 *   "stepIndex": 0,
 *   "matchKeywords": ["welcome", "main menu"],
 *   "reply": "1",
 *   "completion": false,
 *   "isError": false,
 *   "description": "Welcome screen → press 1"
 * }
 *
 * Example full sequence for *999# → 1 → 1 → 2 → 2 → 2:
 *   stepIndex 0 → reply "1"
 *   stepIndex 1 → reply "1"
 *   stepIndex 2 → reply "2"
 *   stepIndex 3 → reply "2"
 *   stepIndex 4 → reply "2", completion true
 */
data class UssdRule(
    @SerializedName("id")           val id: String,
    @SerializedName("stepIndex")    val stepIndex: Int = 0,
    @SerializedName("matchKeywords")val matchKeywords: List<String> = emptyList(),
    @SerializedName("reply")        val reply: String? = null,
    @SerializedName("completion")   val completion: Boolean = false,
    @SerializedName("isError")      val isError: Boolean = false,
    @SerializedName("description")  val description: String? = null
)

/** Top-level auto-flow block in the server config JSON. */
data class AutoFlow(
    @SerializedName("triggerCode") val triggerCode: String = "*999#",
    @SerializedName("steps")       val steps: List<UssdRule> = emptyList()
)

/** Full v2 config root (supports both categories + autoFlow). */
data class UssdConfigV2(
    @SerializedName("version")         val version: String,
    @SerializedName("operator")        val operator: String,
    @SerializedName("lastUpdated")     val lastUpdated: Long,
    @SerializedName("defaultUssdCode") val defaultUssdCode: String = "*999#",
    @SerializedName("autoFlow")        val autoFlow: AutoFlow? = null,
    @SerializedName("rules")           val rules: List<UssdRule> = emptyList(),
    @SerializedName("categories")      val categories: List<UssdCategory> = emptyList()
)
