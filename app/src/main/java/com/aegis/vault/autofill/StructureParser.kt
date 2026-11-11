package com.aegis.vault.autofill

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

class ParsedForm(
    val packageName: String,
    val webDomain: String?,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val usernameValue: String,
    val passwordValue: String,
) {
    val fillable: Boolean get() = usernameId != null || passwordId != null
}

/**
 * Finds the username and password fields of a login form using, in order of trust:
 * autofill hints, HTML attributes (web pages), input types, then id/hint text heuristics.
 */
object StructureParser {
    private enum class Kind { NONE, TEXT, USERNAME, PASSWORD }

    private class State {
        var webDomain: String? = null
        var username: ViewNode? = null
        var password: ViewNode? = null
        var lastText: ViewNode? = null
    }

    fun parse(structure: AssistStructure): ParsedForm {
        val state = State()
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, state)
        }
        return ParsedForm(
            packageName = structure.activityComponent?.packageName.orEmpty(),
            webDomain = state.webDomain,
            usernameId = state.username?.autofillId,
            passwordId = state.password?.autofillId,
            usernameValue = textOf(state.username),
            passwordValue = textOf(state.password),
        )
    }

    private fun textOf(node: ViewNode?): String {
        if (node == null) return ""
        val v = node.autofillValue
        if (v != null && v.isText) return v.textValue.toString()
        return node.text?.toString().orEmpty()
    }

    private fun walk(node: ViewNode?, s: State) {
        if (node == null) return
        if (s.webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { s.webDomain = it }
        when (classify(node)) {
            Kind.PASSWORD -> if (s.password == null) {
                s.password = node
                // No explicit username field: assume the text field just before the password.
                if (s.username == null) s.username = s.lastText
            }
            Kind.USERNAME -> if (s.password == null) s.username = node
            Kind.TEXT -> if (s.password == null) s.lastText = node
            Kind.NONE -> Unit
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), s)
    }

    private fun classify(node: ViewNode): Kind {
        if (node.autofillId == null) return Kind.NONE
        if (node.visibility != View.VISIBLE) return Kind.NONE

        val hints = node.autofillHints?.map { it.lowercase() }.orEmpty()
        if (hints.any { it.contains("password") }) return Kind.PASSWORD
        if (hints.any { it.contains("username") || it.contains("email") || it.contains("phone") }) return Kind.USERNAME

        val html = node.htmlInfo?.takeIf { it.tag.equals("input", ignoreCase = true) }
        var htmlType: String? = null
        val htmlDesc = StringBuilder()
        html?.attributes?.forEach { attr ->
            when (attr.first?.lowercase()) {
                "type" -> htmlType = attr.second?.lowercase()
                "name", "id", "autocomplete", "placeholder", "aria-label" -> htmlDesc.append(' ').append(attr.second.orEmpty())
            }
        }
        if (html != null) {
            if (htmlType == "password") return Kind.PASSWORD
            if (htmlType != null && htmlType !in setOf("text", "email", "tel", "number", "")) return Kind.NONE
        }

        val editable = html != null || node.className?.contains("EditText") == true
        if (!editable) return Kind.NONE
        if (html == null && node.autofillType != View.AUTOFILL_TYPE_TEXT) return Kind.NONE

        val cls = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        ) return Kind.PASSWORD
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return Kind.PASSWORD
        if (htmlType == "email") return Kind.USERNAME
        if (cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        ) return Kind.USERNAME

        val desc = (node.idEntry.orEmpty() + " " + node.hint.orEmpty() + htmlDesc).lowercase()
        if (desc.contains("pass") || desc.contains("pwd")) return Kind.PASSWORD
        if (USERNAME_WORDS.any { desc.contains(it) }) return Kind.USERNAME
        if (desc.contains("search") || desc.contains("captcha") || desc.contains("otp")) return Kind.NONE
        return Kind.TEXT
    }

    private val USERNAME_WORDS = listOf("user", "email", "e-mail", "login", "account", "phone", "mobile", "identifier")
}
