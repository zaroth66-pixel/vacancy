package com.ethiotelecom.ussd.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import com.ethiotelecom.ussd.model.DialResult

object UssdDialer {

    fun dial(context: Context, rawCode: String, userInput: String? = null): DialResult {
        return try {
            val finalCode = buildFinalCode(rawCode, userInput)
            val encoded   = finalCode.replace("#", Uri.encode("#"))
            val intent    = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encoded")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            DialResult(success = true, code = finalCode)
        } catch (e: SecurityException) {
            DialResult(success = false, code = rawCode, message = "CALL_PHONE permission denied")
        } catch (e: Exception) {
            DialResult(success = false, code = rawCode, message = e.message)
        }
    }

    fun hasTelephony(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm != null && tm.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    private fun buildFinalCode(template: String, input: String?): String =
        if (!input.isNullOrBlank() && template.contains("{INPUT}"))
            template.replace("{INPUT}", input.trim())
        else template

    fun normalizeForDisplay(code: String): String = code.replace("{INPUT}", "___")
}
