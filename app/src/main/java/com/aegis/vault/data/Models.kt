package com.aegis.vault.data

enum class ItemType(val label: String, val plural: String) {
    LOGIN("Login", "Logins"),
    NOTE("Secure note", "Notes"),
    CARD("Payment card", "Cards"),
    IDENTITY("Identity", "Identities"),
    TOTP("Authenticator", "Authenticator"),
}

/**
 * A decrypted vault record WITHOUT its secret part. [meta] holds only what is needed to list,
 * search and match an entry; passwords, note bodies, card and identity details are never here.
 */
data class VaultItem(
    val id: String,
    val type: ItemType,
    val meta: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    operator fun get(key: String): String = meta[key].orEmpty()

    val title: String get() = this[K.TITLE]
    val username: String get() = this[K.USERNAME]
    val url: String get() = this[K.URL]
    val category: String get() = this[K.CATEGORY]
    val favourite: Boolean get() = this[K.FAVOURITE] == "1"
    val packages: List<String> get() = this[K.PACKAGES].split(',').filter { it.isNotBlank() }

    val subtitle: String
        get() = when (type) {
            ItemType.LOGIN -> username.ifBlank { url.ifBlank { "Login" } }
            ItemType.NOTE -> "Secure note"
            ItemType.CARD -> "Payment card"
            ItemType.IDENTITY -> this[K.KIND].ifBlank { "Identity" }
            ItemType.TOTP -> username
        }

    fun with(vararg pairs: Pair<String, String>): VaultItem = copy(meta = meta + pairs)

    /** Search only ever looks at non-secret metadata. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return title.contains(q, true) || username.contains(q, true) ||
            url.contains(q, true) || category.contains(q, true)
    }
}

/** Field keys shared by the editor, the detail screen, autofill and backups. */
object K {
    const val TITLE = "title"
    const val USERNAME = "username"
    const val URL = "url"
    const val CATEGORY = "category"
    const val FAVOURITE = "favourite"
    const val PACKAGES = "packages"
    const val KIND = "kind"
    const val PW_CHANGED = "pwChanged"
    const val PASSWORD = "password"
    const val NOTES = "notes"
    const val BODY = "body"
    const val TOTP_SECRET = "totpSecret"
    const val TOTP_ALGO = "algo"
    const val TOTP_DIGITS = "digits"
    const val TOTP_PERIOD = "period"
}

enum class FieldKind { TEXT, PASSWORD, MULTILINE, NUMBER, URL }

data class FieldSpec(
    val key: String,
    val label: String,
    val secret: Boolean,
    val kind: FieldKind = FieldKind.TEXT,
)

fun specsFor(type: ItemType): List<FieldSpec> = when (type) {
    ItemType.LOGIN -> listOf(
        FieldSpec(K.TITLE, "App or website name", false),
        FieldSpec(K.USERNAME, "Username or email", false),
        FieldSpec(K.PASSWORD, "Password", true, FieldKind.PASSWORD),
        FieldSpec(K.URL, "Website URL (optional)", false, FieldKind.URL),
        FieldSpec(K.CATEGORY, "Category", false),
        FieldSpec(K.NOTES, "Notes", true, FieldKind.MULTILINE),
    )
    ItemType.NOTE -> listOf(
        FieldSpec(K.TITLE, "Title", false),
        FieldSpec(K.CATEGORY, "Category", false),
        FieldSpec(K.BODY, "Note", true, FieldKind.MULTILINE),
    )
    ItemType.CARD -> listOf(
        FieldSpec(K.TITLE, "Card name", false),
        FieldSpec("holder", "Cardholder name", true),
        FieldSpec("number", "Card number", true, FieldKind.NUMBER),
        FieldSpec("expiry", "Expiry (MM/YY)", true),
        FieldSpec("cvv", "Security code", true, FieldKind.NUMBER),
        FieldSpec("pin", "PIN", true, FieldKind.NUMBER),
        FieldSpec(K.NOTES, "Notes", true, FieldKind.MULTILINE),
    )
    ItemType.IDENTITY -> listOf(
        FieldSpec(K.TITLE, "Title", false),
        FieldSpec(K.KIND, "Document type (passport, ID, licence…)", false),
        FieldSpec("fullName", "Full name", true),
        FieldSpec("number", "Document number", true),
        FieldSpec("country", "Country / issuing authority", true),
        FieldSpec("dob", "Date of birth", true),
        FieldSpec("issued", "Issue date", true),
        FieldSpec("expires", "Expiry date", true),
        FieldSpec("address", "Address", true, FieldKind.MULTILINE),
        FieldSpec(K.NOTES, "Notes", true, FieldKind.MULTILINE),
    )
    ItemType.TOTP -> emptyList()
}

enum class LogEvent(val label: String) {
    VAULT_CREATED("Vault created"),
    UNLOCK("Vault unlocked"),
    UNLOCK_FAILED("Unlock attempt failed"),
    ITEM_ADDED("Entry added"),
    ITEM_EDITED("Entry edited"),
    ITEM_DELETED("Entry deleted"),
    SECRET_REVEALED("Protected details revealed"),
    SECRET_COPIED("Protected value copied"),
    AUTOFILL("Credential autofilled"),
    AUTOFILL_SAVE("Credential saved from autofill"),
    TOTP_ADDED("Authenticator account added"),
    TOTP_DELETED("Authenticator account removed"),
    HEALTH_CHECK("Security check run"),
    BACKUP_CREATED("Encrypted backup created"),
    BACKUP_RESTORED("Backup restored"),
    LOG_CLEARED("Activity history cleared"),
}

data class LogEntry(val time: Long, val event: LogEvent?, val rawEvent: String, val detail: String?)
