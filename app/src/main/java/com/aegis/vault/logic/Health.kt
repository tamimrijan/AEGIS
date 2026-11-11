package com.aegis.vault.logic

import com.aegis.vault.data.VaultItem
import kotlin.math.roundToInt

data class HealthIssue(
    val itemId: String,
    val title: String,
    val username: String,
    val weak: Boolean,
    val reused: Boolean,
    val duplicate: Boolean,
    val old: Boolean,
)

data class HealthReport(
    val total: Int,
    val weak: Int,
    val reused: Int,
    val duplicate: Int,
    val old: Int,
    val score: Int,
    val issues: List<HealthIssue>,
    val recommendations: List<String>,
    val checkedAt: Long,
) {
    val grade: String
        get() = when {
            total == 0 -> "No logins yet"
            score >= 90 -> "Excellent"
            score >= 75 -> "Good"
            score >= 50 -> "Needs attention"
            else -> "At risk"
        }
}

/** Entirely local analysis. Passwords are compared in memory and never leave this function. */
object Health {
    const val OLD_AFTER_DAYS = 365

    class Entry(
        val id: String,
        val title: String,
        val username: String,
        val target: String,
        val password: String,
        val changedAt: Long,
    )

    fun analyze(entries: List<Entry>, now: Long): HealthReport {
        val withPw = entries.filter { it.password.isNotEmpty() }
        val byPassword = withPw.groupBy { it.password }
        val byIdentity = withPw.groupBy {
            Triple(Matcher.registrable(Matcher.host(it.target) ?: it.target.lowercase().trim()), it.username.lowercase().trim(), it.password)
        }
        val oldCutoff = now - OLD_AFTER_DAYS * 86_400_000L

        val issues = withPw.mapNotNull { e ->
            val weak = StrengthMeter.estimate(e.password).score <= 1
            val reused = (byPassword[e.password]?.size ?: 0) > 1
            val duplicate = byIdentity.values.any { g -> g.size > 1 && g.any { it.id == e.id } }
            val old = e.changedAt in 1 until oldCutoff
            if (weak || reused || duplicate || old) HealthIssue(e.id, e.title, e.username, weak, reused, duplicate, old) else null
        }.sortedWith(compareByDescending<HealthIssue> { it.weak }.thenByDescending { it.reused }.thenBy { it.title.lowercase() })

        val total = entries.size
        val weak = issues.count { it.weak }
        val reused = issues.count { it.reused }
        val duplicate = issues.count { it.duplicate }
        val old = issues.count { it.old }
        val score = if (total == 0) 100 else {
            val penalty = (0.45 * weak + 0.30 * reused + 0.10 * duplicate + 0.15 * old) / total
            (100 * (1 - penalty)).roundToInt().coerceIn(0, 100)
        }

        val recs = buildList {
            if (weak > 0) add("Replace $weak weak password${s(weak)} with generated ones of 16+ characters.")
            if (reused > 0) add("$reused login${s(reused)} share a password. Give every account its own so one breach cannot spread.")
            if (duplicate > 0) add("$duplicate duplicate entr${if (duplicate == 1) "y" else "ies"} found. Remove the copies to keep the vault tidy.")
            if (old > 0) add("$old password${s(old)} ${if (old == 1) "has" else "have"} not changed in over a year. Rotate the important ones.")
            val empty = entries.size - withPw.size
            if (empty > 0) add("$empty login${s(empty)} ${if (empty == 1) "has" else "have"} no password stored.")
            if (isEmpty() && total > 0) add("Everything looks healthy. Keep an up-to-date encrypted backup.")
            if (total == 0) add("Add your logins to the vault to see a security score.")
        }
        return HealthReport(total, weak, reused, duplicate, old, score, issues, recs, now)
    }

    private fun s(n: Int) = if (n == 1) "" else "s"
}

/** Decides which saved logins belong to the app or website asking for autofill. */
object Matcher {
    /** Only these packages are trusted to report the web domain of the page being shown. */
    val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
        "org.chromium.chrome", "com.android.browser", "com.mi.globalbrowser", "com.huawei.browser",
        "org.torproject.torbrowser", "com.ecosia.android",
    )

    private val TWO_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.in", "net.in",
        "org.in", "com.bd", "net.bd", "org.bd", "gov.bd", "edu.bd", "co.jp", "ne.jp", "or.jp", "com.br",
        "com.cn", "com.hk", "com.sg", "com.my", "com.pk", "com.tr", "com.mx", "com.ar", "co.za",
        "co.kr", "com.tw", "com.ph", "com.vn", "co.id", "com.sa", "com.eg", "com.ng", "co.il", "com.ua",
    )

    /** Extracts a lower-case host without scheme, credentials, port, path or leading "www.". */
    fun host(url: String): String? {
        var s = url.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':')
        s = s.removePrefix("www.")
        return s.takeIf { it.contains('.') && !it.contains(' ') }
    }

    /** Approximate registrable domain (eTLD+1) using a short list of two-part public suffixes. */
    fun registrable(host: String): String {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_PART_SUFFIXES) parts.takeLast(3).joinToString(".") else lastTwo
    }

    fun sameSite(itemUrl: String, webDomain: String): Boolean {
        val a = host(itemUrl) ?: return false
        val b = host(webDomain) ?: return false
        return registrable(a) == registrable(b)
    }

    /**
     * Strict matches that are safe to offer directly:
     *  - in a trusted browser: logins whose URL is on the same registrable domain;
     *  - in any other app: logins explicitly linked to that package.
     */
    fun match(items: List<VaultItem>, packageName: String, webDomain: String?): List<VaultItem> {
        val web = webDomain?.takeIf { packageName in BROWSERS && it.isNotBlank() }
        return items.filter { item ->
            if (web != null) sameSite(item.url, web) else packageName in item.packages
        }
    }

    /**
     * Looser guesses for native apps (e.g. "facebook" in com.facebook.katana for facebook.com).
     * These are only ever shown as suggestions the user must pick by hand.
     */
    fun suggestions(items: List<VaultItem>, packageName: String): List<VaultItem> {
        val segments = packageName.lowercase().split('.').filter { it.length >= 4 && it !in GENERIC }
        if (segments.isEmpty()) return emptyList()
        return items.filter { item ->
            val label = host(item.url)?.let { registrable(it).substringBefore('.') }
            val title = item.title.lowercase().filter { it.isLetterOrDigit() }
            segments.any { seg -> seg == label || (title.length >= 4 && (seg == title || seg.contains(title))) }
        }
    }

    private val GENERIC = setOf("android", "mobile", "apps", "application", "client", "lite", "google", "main")
}
