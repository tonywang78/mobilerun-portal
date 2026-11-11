package com.mobilerun.caragent.credentials

/**
 * Secrets are referenced by id only in LLM context (mobilerun type_secret pattern).
 */
class CredentialStore {
    private val secrets = linkedMapOf<String, String>()
    var pendingUserPrompt: String? = null
        private set

    fun put(id: String, value: String) {
        secrets[id] = value
    }

    fun get(id: String): String? = secrets[id]

    fun ids(): Set<String> = secrets.keys

    fun requestUser(prompt: String) {
        pendingUserPrompt = prompt
    }

    fun clearPending() {
        pendingUserPrompt = null
    }

    /**
     * Detects visible credential UI text. Avoids matching a11y JSON fields like `isPassword`.
     */
    fun needsUserForCredentialScreen(screenText: String): Boolean {
        if (screenText.isBlank()) return false
        // Strip common a11y boolean field names that contain marker substrings.
        val scrubbed = screenText
            .replace(Regex("\"isPassword\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\"password\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE), "")
        val patterns = listOf(
            Regex("""\b(password|passwd|otp|2fa)\b""", RegexOption.IGNORE_CASE),
            Regex("""密码|验证码|支付密码"""),
            Regex("""\bsign[\s-]?in\b""", RegexOption.IGNORE_CASE),
            Regex("""\blog[\s-]?in\b""", RegexOption.IGNORE_CASE),
            Regex("""登录|登陆"""),
            Regex("""\bpayment\b|\bcheckout\b""", RegexOption.IGNORE_CASE),
            Regex("""支付|付款"""),
        )
        return patterns.any { it.containsMatchIn(scrubbed) }
    }
}
