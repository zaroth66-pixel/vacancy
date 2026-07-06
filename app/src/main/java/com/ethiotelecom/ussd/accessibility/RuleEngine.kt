package com.ethiotelecom.ussd.accessibility

import android.util.Log
import com.ethiotelecom.ussd.model.UssdRule

/**
 * Stateful step-ordered rule engine.
 *
 * Maintains [currentStepIndex] as the USSD session advances.
 * Each evaluate() call:
 *   1. Finds a rule whose stepIndex == currentStepIndex AND whose matchKeywords
 *      appear (case-insensitive) in the dialog text.
 *   2. Advances the cursor and returns the MatchResult.
 *   3. If no match at current step, tries currentStep+1 (lookahead) to handle
 *      dialers that occasionally collapse two screens into one.
 *   4. Error rules use stepIndex = -1 and are checked on every evaluation.
 */
class RuleEngine(private val rules: List<UssdRule>) {

    private val tag = "RuleEngine"

    var currentStepIndex: Int = 0
        private set

    private var lastMatchedId: String? = null

    // ── Result ────────────────────────────────────────────────────────────────

    data class MatchResult(
        val rule: UssdRule,
        val reply: String?,
        val isCompletion: Boolean,
        val isError: Boolean,
        val stepFired: Int
    )

    // ── Core evaluate ─────────────────────────────────────────────────────────

    fun evaluate(screenText: String): MatchResult? {
        val normalized = screenText.lowercase().trim()

        // Always check error rules first (stepIndex == -1)
        val errorRule = rules.filter { it.stepIndex == -1 }
            .firstOrNull { r -> r.matchKeywords.any { kw -> normalized.contains(kw.lowercase()) } }
        if (errorRule != null) return fire(errorRule, forcedStep = currentStepIndex)

        // Primary step match
        val primary = matchAtStep(normalized, currentStepIndex)
        if (primary != null) return fire(primary)

        // Lookahead +1 (skipped confirmation screen)
        val lookahead = matchAtStep(normalized, currentStepIndex + 1)
        if (lookahead != null) {
            Log.w(tag, "Lookahead matched step ${lookahead.stepIndex}, advancing from $currentStepIndex")
            currentStepIndex++
            return fire(lookahead)
        }

        Log.w(tag, "No rule matched at step $currentStepIndex — screen: ${normalized.take(100)}")
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun matchAtStep(normalizedText: String, stepIndex: Int): UssdRule? =
        rules.filter { it.stepIndex == stepIndex }
            .firstOrNull { rule ->
                rule.matchKeywords.any { kw -> normalizedText.contains(kw.lowercase()) }
            }

    private fun fire(rule: UssdRule, forcedStep: Int = currentStepIndex): MatchResult {
        val fired = forcedStep
        lastMatchedId = rule.id
        if (!rule.completion && !rule.isError && rule.stepIndex >= 0) currentStepIndex++
        Log.d(tag, "Fired rule=${rule.id} step=$fired reply=${rule.reply} done=${rule.completion}")
        return MatchResult(
            rule         = rule,
            reply        = rule.reply,
            isCompletion = rule.completion,
            isError      = rule.isError,
            stepFired    = fired
        )
    }

    // ── Control ───────────────────────────────────────────────────────────────

    fun reset() {
        currentStepIndex = 0
        lastMatchedId    = null
        Log.d(tag, "RuleEngine reset")
    }

    fun lastMatchedRuleId(): String? = lastMatchedId
    val totalSteps: Int get() = rules.filter { it.stepIndex >= 0 }.maxOfOrNull { it.stepIndex + 1 } ?: 0
}
