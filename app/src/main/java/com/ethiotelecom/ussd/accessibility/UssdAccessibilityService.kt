package com.ethiotelecom.ussd.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ethiotelecom.ussd.UssdApplication
import com.ethiotelecom.ussd.model.AutoFlow
import com.ethiotelecom.ussd.model.UssdRule
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Accessibility service — drives the USSD step sequencer.
 *
 * Flow:
 *  1. MainActivity taps START → calls startAutoSession()
 *  2. startAutoSession() loads autoFlow config, resets RuleEngine
 *  3. Android dials the trigger code (e.g. *999#)
 *  4. Each USSD dialog open/update fires onAccessibilityEvent
 *  5. Screen text extracted → RuleEngine.evaluate()
 *  6. Matching rule's reply injected into dialog EditText
 *  7. Send button tapped automatically
 *  8. Step cursor advances; repeat until completion/error/maxSteps
 *  9. Result written to SharedPreferences → MainActivity reads it
 */
class UssdAccessibilityService : AccessibilityService() {

    private val tag           = "UssdA11y"
    private val handler       = Handler(Looper.getMainLooper())
    private var ruleEngine: RuleEngine? = null
    private var sessionActive = false
    private var stepCount     = 0
    private val maxSteps      = 30
    private val replyDelayMs  = 700L
    private val sendDelayMs   = 250L

    private var lastScreenText = ""
    private var lastScreenTime = 0L
    private val dedupeMs       = 400L

    private val dialerPackages = setOf(
        "com.android.phone",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.mediatek.phone",
        "com.transsion.dialer",
        "com.hihonor.android.dialer"
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "Service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(tag, "Interrupted")
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in dialerPackages) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        if (!looksLikeUssdDialog(root)) return

        val screenText = extractAllText(root)
        if (screenText.isBlank()) return

        val now = System.currentTimeMillis()
        if (screenText == lastScreenText && (now - lastScreenTime) < dedupeMs) return
        lastScreenText = screenText
        lastScreenTime = now

        Log.d(tag, "USSD screen [step=$stepCount]: ${screenText.take(150)}")
        broadcastScreenText(screenText)

        if (!sessionActive) return
        val engine = ruleEngine ?: return

        if (stepCount >= maxSteps) {
            endSession("MAX_STEPS", screenText, isError = true)
            return
        }

        val result = engine.evaluate(screenText) ?: return

        if (result.isCompletion || result.isError) {
            endSession(result.rule.id, screenText, isError = result.isError)
            return
        }

        // Schedule reply injection
        handler.postDelayed({
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            if (result.reply != null) {
                injectReply(freshRoot, result.reply)
            } else {
                clickSendButton(freshRoot)
            }
            stepCount++
        }, replyDelayMs)
    }

    // ── Session control ───────────────────────────────────────────────────────

    fun startAutoSession(): Boolean {
        val flow = loadAutoFlow() ?: run {
            Log.e(tag, "No auto-flow config found")
            return false
        }
        ruleEngine    = RuleEngine(flow.steps)
        sessionActive = true
        stepCount     = 0
        lastScreenText = ""
        lastScreenTime = 0L
        Log.d(tag, "Session started — ${flow.steps.size} steps, trigger=${flow.triggerCode}")
        return true
    }

    fun stopSession() {
        sessionActive = false
        ruleEngine?.reset()
        stepCount = 0
        Log.d(tag, "Session stopped manually")
    }

    // ── Config loading ────────────────────────────────────────────────────────

    private fun loadAutoFlow(): AutoFlow? {
        return try {
            val prefs = (application as UssdApplication).preferenceManager
            val json  = prefs.cachedConfigJson
                ?: loadAssetJson()
                ?: return null
            parseAutoFlow(json)
        } catch (e: Exception) {
            Log.e(tag, "loadAutoFlow: ${e.message}")
            null
        }
    }

    private fun loadAssetJson(): String? = try {
        application.assets.open("config.json").bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    private fun parseAutoFlow(json: String): AutoFlow? = try {
        val obj  = JsonParser.parseString(json).asJsonObject
        val gson = Gson()
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
    } catch (_: Exception) { null }

    // ── Dialog detection ──────────────────────────────────────────────────────

    private fun looksLikeUssdDialog(root: AccessibilityNodeInfo): Boolean =
        root.findAccessibilityNodeInfosByViewId("android:id/input").isNotEmpty() ||
        root.findAccessibilityNodeInfosByViewId("android:id/message").isNotEmpty()

    // ── Text extraction ───────────────────────────────────────────────────────

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectText(node, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let               { if (it.isNotBlank()) sb.appendLine(it) }
        node.contentDescription?.let { if (it.isNotBlank()) sb.appendLine(it) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, sb) }
    }

    // ── Reply injection ───────────────────────────────────────────────────────

    private fun injectReply(root: AccessibilityNodeInfo, reply: String) {
        val input = findInputField(root) ?: run {
            Log.w(tag, "No input field — tapping send directly")
            handler.postDelayed({ clickSendButton(root) }, sendDelayMs)
            return
        }

        input.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Log.d(tag, "Injected reply='$reply'")

        handler.postDelayed({
            rootInActiveWindow?.let { clickSendButton(it) }
        }, sendDelayMs)
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("android:id/input").firstOrNull()?.let { return it }
        return findEditText(root)
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditText(node.getChild(i) ?: continue)?.let { return it }
        }
        return null
    }

    private fun clickSendButton(root: AccessibilityNodeInfo) {
        listOf("android:id/button1", "android:id/button_ok", "android:id/sendButton").forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let {
                if (it.isEnabled) {
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(tag, "Send clicked via $id")
                    return
                }
            }
        }
        findClickableButton(root, setOf("send", "ok", "yes", "continue", "next", "ቀጥል", "አዎ"))
    }

    private fun findClickableButton(node: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        if (node.isClickable && labels.any { text.contains(it) }) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            if (findClickableButton(node.getChild(i) ?: continue, labels)) return true
        }
        return false
    }

    // ── Session end ───────────────────────────────────────────────────────────

    private fun endSession(ruleId: String, finalText: String, isError: Boolean) {
        sessionActive = false
        ruleEngine?.reset()
        Log.d(tag, "Session ended rule=$ruleId error=$isError")
        broadcastResult(finalText, isError)
    }

    // ── IPC ───────────────────────────────────────────────────────────────────

    private fun broadcastScreenText(text: String) {
        runCatching {
            val p = (application as UssdApplication).preferenceManager
            p.lastUssdScreenText = text
            p.lastUssdScreenTime = System.currentTimeMillis()
        }
    }

    private fun broadcastResult(finalText: String, isError: Boolean) {
        runCatching {
            val p = (application as UssdApplication).preferenceManager
            p.lastUssdResult        = finalText
            p.lastUssdResultIsError = isError
            p.lastUssdResultTime    = System.currentTimeMillis()
        }
    }

    companion object {
        @Volatile var instance: UssdAccessibilityService? = null
    }
}
