package com.aegis.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vault.aegis
import com.aegis.vault.data.AuthOutcome
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.K
import com.aegis.vault.data.LogEvent
import com.aegis.vault.data.VaultItem
import com.aegis.vault.logic.PassphraseOptions
import com.aegis.vault.logic.PasswordGenerator
import com.aegis.vault.logic.PasswordOptions
import com.aegis.vault.logic.Strength
import com.aegis.vault.logic.Totp
import com.aegis.vault.logic.Wordlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

private fun strengthFromBits(bits: Double) = Strength(
    when {
        bits < 30 -> 0; bits < 45 -> 1; bits < 60 -> 2; bits < 80 -> 3; else -> 4
    }, bits
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun GeneratorTab(nav: Nav) {
    val context = LocalContext.current
    val app = context.aegis
    var passphrase by remember { mutableStateOf(false) }
    var po by remember { mutableStateOf(PasswordOptions()) }
    var pp by remember { mutableStateOf(PassphraseOptions()) }
    var tick by remember { mutableIntStateOf(0) }
    val generated = remember(passphrase, po, pp, tick) {
        if (passphrase) PasswordGenerator.passphrase(pp) else PasswordGenerator.password(po)
    }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            SegmentedButton(selected = !passphrase, onClick = { passphrase = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Password") }
            SegmentedButton(selected = passphrase, onClick = { passphrase = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Passphrase") }
        }
        Spacer(Modifier.height(14.dp))
        AegisCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            if (generated == null) {
                Text("Enable at least one character set.", color = colors.error)
            } else {
                Text(
                    buildAnnotatedString {
                        generated.value.forEach { c ->
                            val color = when {
                                c.isDigit() -> colors.tertiary
                                !c.isLetter() -> colors.primary
                                else -> colors.onSurface
                            }
                            withStyle(SpanStyle(color = color)) { append(c) }
                        }
                    },
                    fontFamily = AegisFonts.Mono, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(16.dp))
                StrengthBar(strengthFromBits(generated.bits))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalIconButton(onClick = { tick++ }) { Icon(Icons.Rounded.Refresh, "Generate again") }
                FilledTonalButton(
                    onClick = {
                        generated?.let {
                            app.clipboard.copy(it.value)
                            context.toast("Copied · clipboard clears in ${app.prefs.clipboardSeconds.value}s")
                        }
                    },
                    enabled = generated != null, modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy")
                }
                Button(
                    onClick = { generated?.let { nav.push(Route.Edit(null, ItemType.LOGIN, it.value)) } },
                    enabled = generated != null, modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save")
                }
            }
        }

        SectionLabel("Options")
        AegisCard(Modifier.fillMaxWidth()) {
            if (!passphrase) {
                Row {
                    Text("Length", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${po.length}", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                }
                Slider(value = po.length.toFloat(), onValueChange = { po = po.copy(length = it.roundToInt()) }, valueRange = 8f..64f)
                SwitchRow("Uppercase  A–Z", po.upper) { po = po.copy(upper = it) }
                SwitchRow("Lowercase  a–z", po.lower) { po = po.copy(lower = it) }
                SwitchRow("Numbers  0–9", po.digits) { po = po.copy(digits = it) }
                SwitchRow("Special characters  !@#\$", po.symbols) { po = po.copy(symbols = it) }
                SwitchRow("Avoid look-alikes  (O 0 I l 1)", po.excludeAmbiguous) { po = po.copy(excludeAmbiguous = it) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = po.exclude, onValueChange = { po = po.copy(exclude = it.filter { c -> !c.isWhitespace() }) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Characters to exclude") },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                )
            } else {
                Row {
                    Text("Words", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${pp.words}", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                }
                Slider(value = pp.words.toFloat(), onValueChange = { pp = pp.copy(words = it.roundToInt()) }, valueRange = 3f..10f, steps = 6)
                SwitchRow("Capitalise words", pp.capitalize) { pp = pp.copy(capitalize = it) }
                SwitchRow("Include a number", pp.includeNumber) { pp = pp.copy(includeNumber = it) }
                Spacer(Modifier.height(8.dp))
                Text("Separator", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("-" to "Hyphen", "." to "Dot", "_" to "Underscore", " " to "Space").forEach { (sep, name) ->
                        FilterChip(selected = pp.separator == sep, onClick = { pp = pp.copy(separator = sep) }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Wordlist.words.size}-word list. Use 6+ words for important accounts.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ authenticator

@Composable
fun AuthenticatorTab() {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val all by app.vault.items.collectAsState()
    val accounts = all.filter { it.type == ItemType.TOTP }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VaultItem?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp)) {
            if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Timer, "No accounts yet",
                        "Add the setup key from a service's two-factor settings.",
                    )
                }
            }
            items(accounts, key = { it.id }) { item ->
                val period = item[K.TOTP_PERIOD].toIntOrNull() ?: 30
                val digits = item[K.TOTP_DIGITS].toIntOrNull() ?: 6
                val seconds = now / 1000
                val counter = seconds / period
                val remaining = (period - seconds % period).toInt()
                val code = remember(item.id, counter, item[K.TOTP_SECRET]) {
                    runCatching {
                        Totp.code(Totp.base32Decode(item[K.TOTP_SECRET]), seconds, period, digits, item[K.TOTP_ALGO].ifBlank { "SHA1" })
                    }.getOrDefault("—")
                }
                val urgent = remaining <= 5
                AegisCard(
                    Modifier.animateItem().fillMaxWidth().padding(vertical = 6.dp),
                    onClick = {
                        app.clipboard.copy(code)
                        context.toast("Code copied · clipboard clears in ${app.prefs.clipboardSeconds.value}s")
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ItemAvatar(item, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title.ifBlank { "Account" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.username.isNotBlank()) Text(
                                item.username, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { deleting = item }) {
                            Icon(Icons.Rounded.Delete, "Remove account", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code.chunked((code.length + 1) / 2).joinToString(" "),
                            fontFamily = AegisFonts.Mono, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                            color = if (urgent) AegisColors.Warning else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { remaining / period.toFloat() },
                                modifier = Modifier.size(40.dp), strokeWidth = 3.dp,
                                color = if (urgent) AegisColors.Warning else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                            Text("$remaining", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        ExpressiveFab("Add account", Icons.Rounded.Add, Modifier.align(Alignment.BottomEnd).padding(20.dp)) { adding = true }
    }

    if (adding) AddTotpDialog(onDismiss = { adding = false })

    deleting?.let { item ->
        ConfirmDialog(
            title = "Remove “${item.title}”?",
            text = "You'll stop getting codes for this account. Make sure you can still sign in.",
            confirm = "Remove", danger = true,
            onConfirm = {
                deleting = null
                scope.launch {
                    when (val r = app.vault.authorize(activity, "Remove authenticator account", "Confirm your fingerprint")) {
                        is AuthOutcome.Ok -> {
                            r.keys.wipe()
                            app.vault.delete(item.id)
                            app.vault.log(LogEvent.TOTP_DELETED, item.title)
                        }
                        is AuthOutcome.Fail -> r.message?.let { context.toast(it) }
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AddTotpDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.aegis
    val scope = rememberCoroutineScope()
    var issuer by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var algo by remember { mutableStateOf("SHA1") }
    var digits by remember { mutableIntStateOf(6) }
    var period by remember { mutableIntStateOf(30) }
    val valid = issuer.isNotBlank() && Totp.isValidSecret(secret)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add authenticator account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { input ->
                        val parsed = Totp.parseUri(input)
                        if (parsed != null) {
                            issuer = parsed.issuer; account = parsed.account; secret = parsed.secret
                            algo = parsed.algorithm; digits = parsed.digits; period = parsed.period
                        } else secret = input
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Setup key or otpauth:// link") },
                    supportingText = {
                        if (secret.isNotBlank() && !Totp.isValidSecret(secret)) Text("Not a valid setup key", color = MaterialTheme.colorScheme.error)
                    },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(issuer, { issuer = it }, Modifier.fillMaxWidth(), label = { Text("Service (issuer)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("Account (optional)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SHA1", "SHA256", "SHA512").forEach { a ->
                        FilterChip(selected = algo == a, onClick = { algo = a }, label = { Text(a) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(6, 8).forEach { d ->
                        FilterChip(selected = digits == d, onClick = { digits = d }, label = { Text("$d digits") })
                    }
                    listOf(30, 60).forEach { p ->
                        FilterChip(selected = period == p, onClick = { period = p }, label = { Text("${p}s") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val meta = mapOf(
                        K.TITLE to issuer.trim(), K.USERNAME to account.trim(),
                        K.TOTP_SECRET to secret.uppercase().filter { it != ' ' && it != '-' && it != '=' },
                        K.TOTP_ALGO to algo, K.TOTP_DIGITS to digits.toString(), K.TOTP_PERIOD to period.toString(),
                    )
                    val item = VaultItem(UUID.randomUUID().toString(), ItemType.TOTP, meta, 0, 0)
                    scope.launch {
                        if (app.vault.updateMeta(item, touch = true)) app.vault.log(LogEvent.TOTP_ADDED, item.title)
                    }
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
