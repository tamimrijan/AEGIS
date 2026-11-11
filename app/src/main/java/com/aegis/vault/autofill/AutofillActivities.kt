package com.aegis.vault.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.aegis.vault.aegis
import com.aegis.vault.crypto.VaultKeys
import com.aegis.vault.data.AuthOutcome
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.K
import com.aegis.vault.data.LogEvent
import com.aegis.vault.data.VaultItem
import com.aegis.vault.logic.Matcher
import com.aegis.vault.ui.AegisTheme
import com.aegis.vault.ui.CredentialPicker
import com.aegis.vault.ui.SecureActivity
import com.aegis.vault.ui.SheetHeader
import com.aegis.vault.ui.SheetScaffold
import com.aegis.vault.ui.toast
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Opened by the autofill framework when the user taps an AEGIS suggestion. Requires a biometric
 * Keystore decryption, then returns exactly one credential to the requesting form.
 */
class AutofillAuthActivity : SecureActivity() {
    private var keys: VaultKeys? = null
    private var form: ParsedForm? = null

    private class PickState(val suggested: List<VaultItem>, val all: List<VaultItem>, val target: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val structure: AssistStructure? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
        val parsed = structure?.let(StructureParser::parse)
        if (parsed == null || !parsed.fillable) {
            cancel(); return
        }
        form = parsed
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        var pick by mutableStateOf<PickState?>(null)
        var remember by mutableStateOf(true)

        setContent {
            AegisTheme(aegis.prefs.theme.collectAsState().value) {
                pick?.let { p ->
                    SheetScaffold(onDismiss = { cancel() }) {
                        SheetHeader("Choose a login", "For ${p.target}")
                        Spacer(Modifier.height(16.dp))
                        CredentialPicker(p.suggested, p.all) { item ->
                            lifecycleScope.launch { fill(item, link = remember) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = remember, onCheckedChange = { remember = it })
                            Text(
                                "Remember this login for ${p.target}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            withResumed { }
            val vault = aegis.vault
            when (val r = vault.authorize(this@AutofillAuthActivity, "AEGIS Autofill", "Confirm your fingerprint to fill this login")) {
                is AuthOutcome.Fail -> {
                    r.message?.let { toast(it) }
                    cancel()
                }
                is AuthOutcome.Ok -> {
                    keys = r.keys
                    val logins = vault.decryptItems(r.keys.meta).filter { it.type == ItemType.LOGIN }
                    val direct = itemId?.let { id -> logins.find { it.id == id } }
                    val matches = Matcher.match(logins, parsed.packageName, parsed.webDomain)
                    when {
                        direct != null -> fill(direct, link = false)
                        matches.size == 1 -> fill(matches[0], link = false)
                        else -> pick = PickState(
                            suggested = matches.ifEmpty { Matcher.suggestions(logins, parsed.packageName) },
                            all = logins,
                            target = targetLabel(parsed),
                        )
                    }
                }
            }
        }
    }

    private fun targetLabel(f: ParsedForm): String =
        if (f.packageName in Matcher.BROWSERS && !f.webDomain.isNullOrBlank()) f.webDomain
        else runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(f.packageName, 0)).toString()
        }.getOrDefault(f.packageName)

    private suspend fun fill(item: VaultItem, link: Boolean) {
        val k = keys
        val f = form
        if (k == null || f == null) {
            cancel(); return
        }
        val vault = aegis.vault
        val password = try {
            vault.readSecret(item.id, k)[K.PASSWORD].orEmpty()
        } catch (e: Exception) {
            toast("This entry could not be decrypted"); cancel(); return
        }
        val views = AutofillUi.remoteViews(this, item.title, item.username)
        @Suppress("DEPRECATION")
        val dataset = Dataset.Builder(views)
        @Suppress("DEPRECATION")
        f.usernameId?.let { dataset.setValue(it, AutofillValue.forText(item.username), views) }
        @Suppress("DEPRECATION")
        f.passwordId?.let { dataset.setValue(it, AutofillValue.forText(password), views) }

        if (link) runCatching {
            val isBrowser = f.packageName in Matcher.BROWSERS
            val updated = when {
                isBrowser && item.url.isBlank() && !f.webDomain.isNullOrBlank() -> item.with(K.URL to "https://${f.webDomain}")
                !isBrowser && f.packageName !in item.packages ->
                    item.with(K.PACKAGES to (item.packages + f.packageName).joinToString(","))
                else -> null
            }
            if (updated != null) vault.upsert(updated, null, k, touch = false)
        }
        vault.log(LogEvent.AUTOFILL, item.title, k.meta)
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset.build()))
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) cancel()
    }

    override fun onDestroy() {
        keys?.wipe()
        keys = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM_ID = "aegis.item"
    }
}

/**
 * Shown after the system's own "Save to AEGIS?" confirmation. The user reviews the entry and
 * authorises the write with a fingerprint; an existing login for the same account is updated
 * only after a second, explicit confirmation.
 */
class AutofillSaveActivity : SecureActivity() {
    private var keys: VaultKeys? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val data = PendingSaves.take(intent.getStringExtra(EXTRA_TOKEN))
        if (data == null) {
            finish(); return
        }
        val isBrowser = data.packageName in Matcher.BROWSERS && !data.webDomain.isNullOrBlank()
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(data.packageName, 0)).toString()
        }.getOrDefault(data.packageName)
        val defaultTitle: String = if (isBrowser) Matcher.host(data.webDomain.orEmpty()) ?: data.webDomain.orEmpty() else appLabel

        var title by mutableStateOf(defaultTitle)
        var username by mutableStateOf(data.username)
        var existing by mutableStateOf<VaultItem?>(null)
        var busy by mutableStateOf(false)

        fun sameTarget(item: VaultItem): Boolean =
            if (isBrowser) Matcher.sameSite(item.url, data.webDomain!!) else data.packageName in item.packages

        suspend fun write(update: VaultItem?) {
            val k = keys ?: return
            val vault = aegis.vault
            val now = System.currentTimeMillis().toString()
            if (update != null) {
                val secret = vault.readSecret(update.id, k) + (K.PASSWORD to data.password)
                vault.upsert(update.with(K.PW_CHANGED to now), secret, k)
                vault.log(LogEvent.AUTOFILL_SAVE, "${update.title} (password updated)", k.meta)
                toast("Password updated in AEGIS")
            } else {
                val meta = buildMap {
                    put(K.TITLE, title.trim().ifBlank { defaultTitle })
                    put(K.USERNAME, username.trim())
                    put(K.PW_CHANGED, now)
                    if (isBrowser) put(K.URL, "https://${data.webDomain}") else put(K.PACKAGES, data.packageName)
                }
                val item = VaultItem(UUID.randomUUID().toString(), ItemType.LOGIN, meta, 0, 0)
                vault.upsert(item, mapOf(K.PASSWORD to data.password), k)
                vault.log(LogEvent.AUTOFILL_SAVE, item.title, k.meta)
                toast("Saved to AEGIS")
            }
            finish()
        }

        fun save() {
            if (busy) return
            busy = true
            lifecycleScope.launch {
                val vault = aegis.vault
                when (val r = vault.authorize(this@AutofillSaveActivity, "Save to AEGIS", "Confirm your fingerprint to store this login")) {
                    is AuthOutcome.Fail -> {
                        r.message?.let { toast(it) }
                        busy = false
                    }
                    is AuthOutcome.Ok -> {
                        keys?.wipe()
                        keys = r.keys
                        val match = vault.decryptItems(r.keys.meta).firstOrNull {
                            it.type == ItemType.LOGIN && sameTarget(it) && it.username.equals(username.trim(), ignoreCase = true)
                        }
                        if (match != null) {
                            existing = match // ask before overwriting
                            busy = false
                        } else {
                            runCatching { write(null) }.onFailure { toast("Could not save this login"); busy = false }
                        }
                    }
                }
            }
        }

        setContent {
            AegisTheme(aegis.prefs.theme.collectAsState().value) {
                SheetScaffold(onDismiss = { finish() }) {
                    val current = existing
                    if (current == null) {
                        SheetHeader("Save this login to AEGIS?", "Encrypted on this device only")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Name") },
                            singleLine = true, shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username or email") },
                            singleLine = true, shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            "•".repeat(data.password.length.coerceAtMost(24)), {}, Modifier.fillMaxWidth(),
                            label = { Text("Password") }, readOnly = true, singleLine = true, shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f)) { Text("Not now") }
                            Button(onClick = { save() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save") }
                        }
                    } else {
                        SheetHeader("Update existing login?", "${current.title} · ${current.username}")
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "AEGIS already holds a login for this account. Updating replaces its stored password with the one you just entered.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { lifecycleScope.launch { runCatching { write(null) }.onFailure { toast("Could not save") } } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Save as new") }
                            Button(
                                onClick = { lifecycleScope.launch { runCatching { write(current) }.onFailure { toast("Could not update") } } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Update") }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onDestroy() {
        keys?.wipe()
        keys = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "aegis.save"
    }
}
