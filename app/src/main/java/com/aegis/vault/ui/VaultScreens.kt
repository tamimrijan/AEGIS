package com.aegis.vault.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.aegis.vault.aegis
import com.aegis.vault.crypto.VaultKeys
import com.aegis.vault.data.AuthOutcome
import com.aegis.vault.data.FieldKind
import com.aegis.vault.data.FieldSpec
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.K
import com.aegis.vault.data.LogEvent
import com.aegis.vault.data.VaultItem
import com.aegis.vault.data.specsFor
import com.aegis.vault.logic.Matcher
import com.aegis.vault.logic.PasswordGenerator
import com.aegis.vault.logic.PasswordOptions
import com.aegis.vault.logic.StrengthMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class VaultFilter(val label: String, val type: ItemType?) {
    ALL("All", null),
    LOGIN("Logins", ItemType.LOGIN),
    NOTE("Notes", ItemType.NOTE),
    CARD("Cards", ItemType.CARD),
    IDENTITY("Identity", ItemType.IDENTITY),
    FAVOURITE("Favourites", null),
}

private val ADDABLE = listOf(ItemType.LOGIN, ItemType.NOTE, ItemType.CARD, ItemType.IDENTITY)
private val CATEGORY_SUGGESTIONS = listOf("Personal", "Work", "Finance", "Social", "Email", "Shopping", "Entertainment")

@Composable
fun VaultTab(nav: Nav, home: HomeState) {
    val app = LocalContext.current.aegis
    val all by app.vault.items.collectAsState()
    val corrupted by app.vault.corrupted.collectAsState()
    var adding by remember { mutableStateOf(false) }

    val entries = all.filter { it.type != ItemType.TOTP }
    val categories = entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = entries.filter { item ->
        (home.filter.type == null || item.type == home.filter.type) &&
            (home.filter != VaultFilter.FAVOURITE || item.favourite) &&
            (home.category == null || item.category == home.category) &&
            item.matches(home.query)
    }.sortedWith(compareByDescending<VaultItem> { it.favourite }.thenBy { it.title.lowercase() })

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp)) {
            item {
                SearchField(home.query, { home.query = it }, "Search names, usernames, websites")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VaultFilter.entries.forEach { f ->
                        FilterChip(selected = home.filter == f, onClick = { home.filter = f }, label = { Text(f.label) })
                    }
                }
                if (categories.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { c ->
                            FilterChip(
                                selected = home.category == c,
                                onClick = { home.category = if (home.category == c) null else c },
                                label = { Text(c) },
                                leadingIcon = { Icon(Icons.Rounded.Label, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                if (corrupted > 0) {
                    Spacer(Modifier.height(8.dp))
                    AegisCard(Modifier.fillMaxWidth()) {
                        Text(
                            "$corrupted damaged entr${if (corrupted == 1) "y" else "ies"} can't be shown.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isEmpty()) {
                item {
                    if (entries.isEmpty()) EmptyState(Icons.Rounded.Shield, "Your vault is empty", "Tap Add to save your first entry.")
                    else EmptyState(Icons.Rounded.Search, "Nothing found", "Try another search or filter.")
                }
            }
            // Grouped list: one tonal block whose first and last rows carry the large corners.
            itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
                Surface(
                    onClick = { nav.push(Route.Detail(item.id)) },
                    shape = segmentShape(index, shown.size),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.animateItem().fillMaxWidth().padding(vertical = 1.5.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ItemAvatar(item, 46.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                item.subtitle, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.favourite) Icon(Icons.Rounded.Star, "Favourite", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        ExpressiveFab("Add", Icons.Rounded.Add, Modifier.align(Alignment.BottomEnd).padding(20.dp)) { adding = true }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add to vault") },
            text = {
                Column {
                    ADDABLE.forEach { type ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable { adding = false; nav.push(Route.Edit(null, type)) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(typeIcon(type), null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(type.label, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ detail

@Composable
fun DetailScreen(id: String, nav: Nav) {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val items by app.vault.items.collectAsState()
    val item = items.find { it.id == id }
    if (item == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    // Decrypted protected fields; held only while revealed and hidden again after 30 seconds.
    var secret by remember { mutableStateOf<Map<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(secret) {
        if (secret != null) {
            delay(30_000)
            secret = null
        }
    }

    suspend fun fetch(reason: String): Map<String, String>? =
        when (val r = app.vault.authorize(activity, "Verify it's you", reason)) {
            is AuthOutcome.Ok -> try {
                app.vault.readSecret(id, r.keys)
            } catch (e: Exception) {
                context.toast("This entry failed integrity verification"); null
            } finally {
                r.keys.wipe()
            }
            is AuthOutcome.Fail -> {
                r.message?.let { context.toast(it) }; null
            }
        }

    fun copySecret(spec: FieldSpec) {
        scope.launch {
            val s = secret ?: fetch("Confirm your fingerprint to copy this value") ?: return@launch
            val v = s[spec.key].orEmpty()
            if (v.isEmpty()) {
                context.toast("Nothing is stored in this field"); return@launch
            }
            app.clipboard.copy(v)
            app.vault.log(LogEvent.SECRET_COPIED, item.title)
            context.toast("Copied · clipboard clears in ${app.prefs.clipboardSeconds.value}s")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch { app.vault.updateMeta(item.with(K.FAVOURITE to if (item.favourite) "" else "1")) }
                    }) {
                        Icon(
                            if (item.favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite",
                            tint = if (item.favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { nav.push(Route.Edit(item.id, item.type)) }) { Icon(Icons.Rounded.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                ItemAvatar(item, 64.dp)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOf(item.type.label, item.category).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TonalButton(
                if (secret == null) "Reveal protected details" else "Hide protected details",
                icon = if (secret == null) Icons.Rounded.Fingerprint else Icons.Rounded.VisibilityOff,
                onClick = {
                    if (secret != null) secret = null
                    else scope.launch {
                        fetch("Confirm your fingerprint to reveal protected details")?.let {
                            secret = it
                            app.vault.log(LogEvent.SECRET_REVEALED, item.title)
                        }
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            AegisCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                val specs = specsFor(item.type).filter { it.key != K.TITLE && it.key != K.CATEGORY }
                var first = true
                specs.forEach { spec ->
                    val revealed = secret
                    val value = if (spec.secret) revealed?.get(spec.key) else item[spec.key]
                    val masked = spec.secret && revealed == null
                    if (!masked && value.isNullOrBlank()) return@forEach
                    if (!first) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    first = false
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(spec.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (masked) "••••••••••" else value.orEmpty(),
                                style = if (spec.secret && spec.kind != FieldKind.MULTILINE) MaterialTheme.typography.bodyLarge.copy(fontFamily = AegisFonts.Mono)
                                else MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (spec.kind == FieldKind.URL && !value.isNullOrBlank()) {
                            IconButton(onClick = {
                                val url = if (value.contains("://")) value else "https://$value"
                                (activity as? MainActivity)?.openSystemScreen(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }) { Icon(Icons.Rounded.OpenInNew, "Open website") }
                        }
                        IconButton(onClick = {
                            if (spec.secret) copySecret(spec) else {
                                app.clipboard.copy(value.orEmpty(), sensitive = false)
                                context.toast("${spec.label} copied")
                            }
                        }) { Icon(Icons.Rounded.ContentCopy, "Copy ${spec.label}") }
                    }
                }
            }
            if (item.type == ItemType.LOGIN && item.packages.isNotEmpty()) {
                SectionLabel("Linked apps")
                AegisCard(Modifier.fillMaxWidth()) {
                    Text(item.packages.joinToString("\n") { appLabel(context, it) }, style = MaterialTheme.typography.bodyMedium)
                }
            }
            SectionLabel("History")
            AegisCard(Modifier.fillMaxWidth()) {
                DateRow("Created", item.createdAt)
                DateRow("Modified", item.updatedAt)
                item[K.PW_CHANGED].toLongOrNull()?.let { DateRow("Password changed", it) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete “${item.title}”?",
            text = "This can't be undone.",
            confirm = "Delete", danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    when (val r = app.vault.authorize(activity, "Delete entry", "Confirm your fingerprint to delete “${item.title}”")) {
                        is AuthOutcome.Ok -> {
                            r.keys.wipe()
                            val title = item.title
                            app.vault.delete(id)
                            app.vault.log(LogEvent.ITEM_DELETED, title)
                        }
                        is AuthOutcome.Fail -> r.message?.let { context.toast(it) }
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DateRow(label: String, time: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(formatDate(time), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun appLabel(context: android.content.Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

// ------------------------------------------------------------------ editor

@Composable
fun EditScreen(route: Route.Edit, nav: Nav) {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val existing = remember { route.id?.let { id -> app.vault.items.value.find { it.id == id } } }
    val type = route.type
    val specs = remember { specsFor(type) }
    val values = remember { mutableStateMapOf<String, String>() }
    val holder = remember { arrayOfNulls<VaultKeys>(1) } // keys from the opening fingerprint, reused for saving
    var ready by remember { mutableStateOf(existing == null) }
    var originalPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(route.preset != null) }
    var pickingApp by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (existing == null) {
            route.preset?.let { values[K.PASSWORD] = it }
            return@LaunchedEffect
        }
        specs.filter { !it.secret }.forEach { values[it.key] = existing[it.key] }
        values[K.PACKAGES] = existing[K.PACKAGES]
        when (val r = app.vault.authorize(activity, "Edit entry", "Confirm your fingerprint to edit “${existing.title}”")) {
            is AuthOutcome.Ok -> {
                val secret = runCatching { app.vault.readSecret(existing.id, r.keys) }.getOrNull()
                if (secret == null) {
                    r.keys.wipe()
                    context.toast("This entry failed integrity verification")
                    nav.pop()
                } else {
                    holder[0] = r.keys
                    values.putAll(secret)
                    originalPassword = secret[K.PASSWORD].orEmpty()
                    ready = true
                }
            }
            is AuthOutcome.Fail -> {
                r.message?.let { context.toast(it) }
                nav.pop()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.wipe()
            holder[0] = null
            values.clear()
        }
    }

    fun save() {
        val title = values[K.TITLE].orEmpty().trim().ifBlank {
            if (type == ItemType.LOGIN) Matcher.host(values[K.URL].orEmpty()).orEmpty() else ""
        }
        if (title.isBlank()) {
            error = "Give this entry a name."
            return
        }
        saving = true
        error = null
        scope.launch {
            val keys = holder[0] ?: when (val r = app.vault.authorize(activity, "Save entry", "Confirm your fingerprint to encrypt and save")) {
                is AuthOutcome.Ok -> r.keys.also { holder[0] = it }
                is AuthOutcome.Fail -> {
                    error = r.message
                    saving = false
                    return@launch
                }
            }
            val meta = HashMap(existing?.meta ?: emptyMap())
            specs.filter { !it.secret }.forEach { meta[it.key] = values[it.key].orEmpty().trim() }
            meta[K.TITLE] = title
            val secret = specs.filter { it.secret }.associate { it.key to values[it.key].orEmpty() }
            if (type == ItemType.LOGIN) {
                meta[K.PACKAGES] = values[K.PACKAGES].orEmpty()
                if (existing == null || secret[K.PASSWORD] != originalPassword) meta[K.PW_CHANGED] = System.currentTimeMillis().toString()
            }
            val item = VaultItem(existing?.id ?: UUID.randomUUID().toString(), type, meta, existing?.createdAt ?: 0, 0)
            try {
                app.vault.upsert(item, secret, keys)
                app.vault.health.value = null
                app.vault.log(if (existing == null) LogEvent.ITEM_ADDED else LogEvent.ITEM_EDITED, title)
                nav.pop()
            } catch (e: Exception) {
                error = "The entry could not be saved. (${e.javaClass.simpleName})"
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New ${type.label.lowercase()}" else "Edit ${type.label.lowercase()}") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Rounded.Close, "Cancel") } },
                actions = {
                    Button(onClick = { save() }, enabled = ready && !saving, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(Icons.Rounded.Fingerprint, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (!ready) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for fingerprint…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            specs.forEach { spec ->
                val value = values[spec.key].orEmpty()
                val isPassword = spec.kind == FieldKind.PASSWORD
                OutlinedTextField(
                    value = value,
                    onValueChange = { values[spec.key] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(spec.label) },
                    singleLine = spec.kind != FieldKind.MULTILINE,
                    minLines = if (spec.kind == FieldKind.MULTILINE) 4 else 1,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = if (isPassword) LocalTextStyle.current.copy(fontFamily = AegisFonts.Mono) else LocalTextStyle.current,
                    visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (spec.kind == FieldKind.MULTILINE) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = when (spec.kind) {
                            FieldKind.PASSWORD -> KeyboardType.Password
                            FieldKind.NUMBER -> KeyboardType.NumberPassword
                            FieldKind.URL -> KeyboardType.Uri
                            FieldKind.MULTILINE -> KeyboardType.Text
                            FieldKind.TEXT -> if (spec.secret) KeyboardType.Password else KeyboardType.Text
                        },
                    ),
                    trailingIcon = if (isPassword) {
                        {
                            Row {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide password")
                                }
                                IconButton(onClick = {
                                    PasswordGenerator.password(PasswordOptions())?.let {
                                        values[spec.key] = it.value
                                        showPassword = true
                                    }
                                }) { Icon(Icons.Rounded.Casino, "Generate password") }
                            }
                        }
                    } else null,
                )
                if (isPassword && value.isNotEmpty()) StrengthBar(StrengthMeter.estimate(value), Modifier.padding(horizontal = 4.dp))
                if (spec.key == K.CATEGORY) {
                    val known = (app.vault.items.value.map { it.category } + CATEGORY_SUGGESTIONS).filter { it.isNotBlank() }.distinct()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        known.forEach { c -> AssistChip(onClick = { values[K.CATEGORY] = c }, label = { Text(c) }) }
                    }
                }
            }
            if (type == ItemType.LOGIN) {
                val linked = values[K.PACKAGES].orEmpty().split(',').filter { it.isNotBlank() }
                AegisCard(Modifier.fillMaxWidth()) {
                    Text("Linked app", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (linked.isEmpty()) "None"
                        else linked.joinToString(", ") { appLabel(context, it) },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { pickingApp = true }) { Text(if (linked.isEmpty()) "Choose app" else "Add another") }
                        if (linked.isNotEmpty()) TextButton(onClick = { values[K.PACKAGES] = "" }) { Text("Unlink") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickingApp) {
        AppPickerDialog(
            onPick = { pkg ->
                val current = values[K.PACKAGES].orEmpty().split(',').filter { it.isNotBlank() }
                values[K.PACKAGES] = (current + pkg).distinct().joinToString(",")
                pickingApp = false
            },
            onDismiss = { pickingApp = false },
        )
    }
}

@Composable
private fun AppPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link an app") },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search installed apps") },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list.filter { it.second.contains(query, true) || it.first.contains(query, true) }, key = { it.first }) { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(pkg) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = remember(pkg) {
                                runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                            }
                            if (icon != null) Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                            else Spacer(Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
