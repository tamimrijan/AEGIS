package com.aegis.vault.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aegis.vault.Prefs
import com.aegis.vault.aegis
import com.aegis.vault.crypto.BackupCrypto
import com.aegis.vault.crypto.BackupPayload
import com.aegis.vault.crypto.KeystoreManager
import com.aegis.vault.data.AuthOutcome
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.Vault
import com.aegis.vault.logic.StrengthMeter
import com.aegis.vault.quick.QuickVaultActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SETTINGS_TABS = listOf("General", "Security", "Backup", "About")

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (danger) IconBadge(icon, container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
        else IconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value); onDismiss() })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun SettingsTab(nav: Nav, home: HomeState) {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val main = activity as? MainActivity
    val scope = rememberCoroutineScope()
    val autoLock by app.prefs.autoLockSeconds.collectAsState()
    val clipboard by app.prefs.clipboardSeconds.collectAsState()
    val theme by app.prefs.theme.collectAsState()
    val block by app.prefs.blockScreenshots.collectAsState()
    val dynamic by app.prefs.dynamicColor.collectAsState()
    var dialog by remember { mutableStateOf("") }
    val keyLevel = remember { KeystoreManager.securityLevel() }
    var autofillOn by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        autofillOn = runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }.getOrDefault(false)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        PillTabs(SETTINGS_TABS, home.settingsTab, { home.settingsTab = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        AnimatedContent(
            targetState = home.settingsTab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) },
            label = "settingsTab",
        ) { tab ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (tab) {
                    0 -> {
                        AegisCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                            SettingRow(
                                Icons.Rounded.Bolt, "Autofill service", if (autofillOn) "On" else "Off · tap to turn on",
                                trailing = { if (autofillOn) Icon(Icons.Rounded.CheckCircle, null, tint = AegisColors.Success) },
                            ) {
                                main?.openSystemScreen(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}")))
                            }
                            SettingRow(Icons.Rounded.Apps, "Quick Vault", "Find and copy a login") {
                                context.startActivity(Intent(context, QuickVaultActivity::class.java))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AegisCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                            SettingRow(Icons.Rounded.Palette, "Theme", when (theme) { "light" -> "Light"; "system" -> "System"; else -> "Dark" }) { dialog = "theme" }
                            if (Build.VERSION.SDK_INT >= 31) SettingRow(
                                Icons.Rounded.AutoAwesome, "Wallpaper colours",
                                trailing = { Switch(checked = dynamic, onCheckedChange = { app.prefs.setDynamicColor(it) }) },
                            )
                        }
                    }
                    1 -> {
                        AegisCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                            SettingRow(Icons.Rounded.Fingerprint, "Vault key", keyLevel)
                            SettingRow(Icons.Rounded.Timer, "Auto-lock", Prefs.AUTO_LOCK_OPTIONS.first { it.first == autoLock }.second) { dialog = "autolock" }
                            SettingRow(Icons.Rounded.ContentPaste, "Clear clipboard", "After " + Prefs.CLIPBOARD_OPTIONS.first { it.first == clipboard }.second) { dialog = "clipboard" }
                            SettingRow(
                                Icons.Rounded.VisibilityOff, "Block screenshots",
                                trailing = { Switch(checked = block, onCheckedChange = { app.prefs.setBlockScreenshots(it) }) },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        AegisCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                            SettingRow(Icons.Rounded.History, "Activity log") { nav.push(Route.Log) }
                            SettingRow(Icons.Rounded.DeleteForever, "Erase vault", danger = true) { dialog = "erase" }
                        }
                    }
                    2 -> {
                        AegisCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                            SettingRow(Icons.Rounded.Backup, "Create backup", "Encrypted file with its own password") { dialog = "export" }
                            SettingRow(Icons.Rounded.Restore, "Restore backup", "Import an .aegis file") { main?.startImport() }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "A forgotten backup password can't be recovered.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    else -> AboutContent()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    when (dialog) {
        "autolock" -> ChoiceDialog("Auto-lock", Prefs.AUTO_LOCK_OPTIONS, autoLock, { app.prefs.setAutoLock(it) }) { dialog = "" }
        "clipboard" -> ChoiceDialog("Clear clipboard after", Prefs.CLIPBOARD_OPTIONS, clipboard, { app.prefs.setClipboard(it) }) { dialog = "" }
        "theme" -> ChoiceDialog("Theme", listOf("dark" to "Dark", "light" to "Light", "system" to "System"), theme, { app.prefs.setTheme(it) }) { dialog = "" }
        "export" -> ExportDialog { dialog = "" }
        "erase" -> ConfirmDialog(
            title = "Erase the vault?",
            text = "Every entry and the vault key will be destroyed. Only a backup can bring them back.",
            confirm = "Erase", danger = true,
            onConfirm = {
                dialog = ""
                scope.launch {
                    when (val r = app.vault.authorize(activity, "Erase vault", "Confirm your fingerprint")) {
                        is AuthOutcome.Ok -> {
                            r.keys.wipe()
                            app.vault.erase()
                        }
                        is AuthOutcome.Fail -> r.message?.let { context.toast(it) }
                    }
                }
            },
            onDismiss = { dialog = "" },
        )
    }
}

// ------------------------------------------------------------------ backup

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(16.dp),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun ExportDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val longEnough = password.length >= BackupCrypto.MIN_PASSWORD_LENGTH

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Create backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordField(password, { password = it }, "Backup password (${BackupCrypto.MIN_PASSWORD_LENGTH}+ characters)")
                PasswordField(confirm, { confirm = it }, "Repeat password")
                if (password.isNotEmpty()) StrengthBar(StrengthMeter.estimate(password))
                if (confirm.isNotEmpty() && confirm != password) Text("Passwords don't match.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("If I forget this password, the backup is lost for good.", style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) Text("Encrypting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = longEnough && password == confirm && understood && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = app.vault.authorize(activity, "Create backup", "Confirm your fingerprint")) {
                            is AuthOutcome.Fail -> error = r.message
                            is AuthOutcome.Ok -> try {
                                val items = app.vault.exportAll(r.keys)
                                val now = System.currentTimeMillis()
                                val chars = password.toCharArray()
                                val bytes = withContext(Dispatchers.Default) {
                                    try {
                                        BackupCrypto.encrypt(BackupPayload(now, items), chars)
                                    } finally {
                                        chars.fill('\u0000')
                                    }
                                }
                                app.io.pendingExport = bytes
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
                                (activity as? MainActivity)?.startExport("aegis-backup-$stamp.aegis")
                                onClose()
                            } catch (e: Exception) {
                                error = "The backup could not be created. (${e.javaClass.simpleName})"
                            } finally {
                                r.keys.wipe()
                            }
                        }
                        busy = false
                    }
                },
            ) { Text("Encrypt and save") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * Restore flow shown over any screen: verify the file, ask for the backup password, show what
 * is inside, then require explicit confirmation plus a fingerprint before anything is written.
 */
@Composable
fun RestoreDialog(bytes: ByteArray, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val header = remember(bytes) { runCatching { BackupCrypto.parseHeader(bytes) } }
    var password by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf<BackupPayload?>(null) }
    var replace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val vaultState by app.vault.state.collectAsState()

    val h = header.getOrNull()
    if (h == null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Not an AEGIS backup") },
            text = { Text(header.exceptionOrNull()?.message ?: "This file is not a valid AEGIS backup.") },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
        return
    }

    val loaded = payload
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (loaded == null) "Open backup" else "Restore this backup?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Created ${formatDate(h.createdAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loaded == null) {
                    PasswordField(password, { password = it }, "Backup password")
                    if (busy) Text("Verifying…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val counts = ItemType.entries.mapNotNull { t ->
                        val n = loaded.items.count { it.type == t.name }
                        if (n > 0) "$n ${t.plural.lowercase()}" else null
                    }
                    Text(
                        "Verified · ${loaded.items.size} entries" + if (counts.isEmpty()) "" else "\n${counts.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (vaultState) {
                        Vault.State.UNLOCKED, Vault.State.LOCKED -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replace = false }) {
                                RadioButton(selected = !replace, onClick = { replace = false })
                                Text("Merge into my vault", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replace = true }) {
                                RadioButton(selected = replace, onClick = { replace = true })
                                Text("Replace my vault", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                            if (replace) Text(
                                "Everything now in the vault is deleted first.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Vault.State.INVALIDATED -> Text(
                            "The old vault on this phone is erased and a new key is created.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Vault.State.UNINITIALIZED -> Unit
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (loaded == null) {
                TextButton(
                    enabled = password.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val chars = password.toCharArray()
                            val result = withContext(Dispatchers.Default) {
                                runCatching { BackupCrypto.decrypt(bytes, chars) }.also { chars.fill('\u0000') }
                            }
                            result.onSuccess { payload = it; password = "" }
                                .onFailure { error = it.message ?: "The backup could not be opened." }
                            busy = false
                        }
                    },
                ) { Text("Open") }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val vault = app.vault
                            val outcome = when (vault.state.value) {
                                Vault.State.UNLOCKED, Vault.State.LOCKED ->
                                    vault.authorize(activity, "Restore backup", "Confirm your fingerprint")
                                Vault.State.INVALIDATED -> {
                                    vault.erase()
                                    vault.create(activity)
                                }
                                Vault.State.UNINITIALIZED -> vault.create(activity)
                            }
                            when (outcome) {
                                is AuthOutcome.Fail -> error = outcome.message ?: "Cancelled. Nothing was imported."
                                is AuthOutcome.Ok -> try {
                                    val n = vault.importAll(loaded.items, outcome.keys, replace)
                                    context.toast("Restored $n entries")
                                    onClose()
                                } catch (e: Exception) {
                                    error = "The restore failed. (${e.javaClass.simpleName})"
                                } finally {
                                    outcome.keys.wipe()
                                }
                            }
                            busy = false
                        }
                    },
                ) { Text(if (replace) "Replace vault" else "Restore") }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}
