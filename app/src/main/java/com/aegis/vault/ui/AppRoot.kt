package com.aegis.vault.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.aegis.vault.aegis
import com.aegis.vault.crypto.BiometricAuth
import com.aegis.vault.data.AuthOutcome
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.LogEvent
import com.aegis.vault.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : SecureActivity() {
    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val bytes = aegis.io.pendingExport
            aegis.io.pendingExport = null
            if (uri == null || bytes == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }.isSuccess
                }
                if (ok) {
                    aegis.vault.log(LogEvent.BACKUP_CREATED)
                    toast("Encrypted backup saved")
                } else toast("The backup could not be written to that location")
            }
        }

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        val max = 64 * 1024 * 1024
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > max) error("too large")
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (bytes == null) toast("That file could not be read") else aegis.io.imported.value = bytes
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val mode by aegis.prefs.theme.collectAsState()
            val block by aegis.prefs.blockScreenshots.collectAsState()
            val dark = isDarkTheme(mode)
            LaunchedEffect(block) { applyWindowSecurity() }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AegisTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot() }
            }
        }
    }

    /** Opens the system "save file" picker for an already-encrypted backup. */
    fun startExport(fileName: String) {
        aegis.autoLock.hold()
        runCatching { createDoc.launch(fileName) }.onFailure { toast("No file picker is available on this device") }
    }

    fun startImport() {
        aegis.autoLock.hold()
        runCatching { openDoc.launch(arrayOf("*/*")) }.onFailure { toast("No file picker is available on this device") }
    }

    fun openSystemScreen(intent: Intent) {
        aegis.autoLock.hold()
        runCatching { startActivity(intent) }.onFailure { toast("This settings screen is not available on your device") }
    }
}

sealed interface Route {
    data object Home : Route
    data class Detail(val id: String) : Route
    data class Edit(val id: String?, val type: ItemType, val preset: String? = null) : Route
    data object Log : Route
}

class Nav(private val stack: SnapshotStateList<Route>) {
    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** Vault list state that should survive a trip to a detail screen (never holds secrets). */
class HomeState {
    var tab by mutableIntStateOf(0)
    var settingsTab by mutableIntStateOf(0)
    var query by mutableStateOf("")
    var filter by mutableStateOf(VaultFilter.ALL)
    var category by mutableStateOf<String?>(null)
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.aegis
    val state by app.vault.state.collectAsState()
    val imported by app.io.imported.collectAsState()
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
        label = "vaultState",
    ) { s ->
        when (s) {
            Vault.State.UNINITIALIZED -> OnboardingScreen()
            Vault.State.LOCKED -> LockScreen()
            Vault.State.INVALIDATED -> InvalidatedScreen()
            // Leaving composition on lock discards every remembered value of the unlocked UI.
            Vault.State.UNLOCKED -> MainNav()
        }
    }
    imported?.let { bytes -> RestoreDialog(bytes) { app.io.imported.value = null } }
}

@Composable
private fun MainNav() {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember { Nav(stack) }
    val home = remember { HomeState() }
    BackHandler(enabled = stack.size > 1) { nav.pop() }
    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 14 }) togetherWith fadeOut(tween(120))
        },
        label = "nav",
    ) { route ->
        when (route) {
            Route.Home -> HomeScreen(nav, home)
            is Route.Detail -> DetailScreen(route.id, nav)
            is Route.Edit -> EditScreen(route, nav)
            Route.Log -> LogScreen(nav)
        }
    }
}

private class TabDef(val label: String, val title: String, val outlined: ImageVector, val filled: ImageVector)

private val TABS = listOf(
    TabDef("Vault", "Vault", Icons.Outlined.Shield, Icons.Filled.Shield),
    TabDef("Generator", "Generator", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    TabDef("Codes", "Authenticator", Icons.Outlined.Timer, Icons.Filled.Timer),
    TabDef("Security", "Security", Icons.Outlined.MonitorHeart, Icons.Filled.MonitorHeart),
    TabDef("Settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * Expressive navigation bar: the active destination gets a filled icon inside a pill that
 * springs open, and a heavier label; inactive ones stay outlined and quiet.
 */
@Composable
private fun AegisNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(76.dp).padding(horizontal = 6.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS.forEachIndexed { i, tab ->
                val active = i == selected
                val pillWidth by animateDpAsState(
                    if (active) 64.dp else 36.dp, spring(dampingRatio = 0.5f, stiffness = 450f), label = "pill",
                )
                val pillColor by animateColorAsState(if (active) colors.primaryContainer else Color.Transparent, label = "pillColor")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(
                            selected = active, role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { onSelect(i) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.height(34.dp).width(pillWidth).clip(CircleShape).background(pillColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (active) tab.filled else tab.outlined, contentDescription = null,
                            tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        tab.label, maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) colors.onSurface else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(nav: Nav, home: HomeState) {
    val app = LocalContext.current.aegis
    val lockSource = remember { MutableInteractionSource() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { Box(Modifier.padding(start = 20.dp, end = 6.dp)) { AegisLogo(34.dp) } },
                title = { Text(TABS[home.tab].title, style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    FilledTonalButton(
                        onClick = { app.vault.lock(manual = true) },
                        interactionSource = lockSource,
                        shape = morphShape(lockSource, rest = 20.dp, pressed = 10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Emergency lock", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lock")
                    }
                    Spacer(Modifier.width(14.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { AegisNavBar(home.tab) { home.tab = it } },
    ) { padding ->
        Crossfade(targetState = home.tab, animationSpec = tween(180), label = "tab", modifier = Modifier.padding(padding)) { tab ->
            when (tab) {
                0 -> VaultTab(nav, home)
                1 -> GeneratorTab(nav)
                2 -> AuthenticatorTab()
                3 -> SecurityTab(nav)
                else -> SettingsTab(nav, home)
            }
        }
    }
}

// ------------------------------------------------------------------ onboarding / lock / invalidated

@Composable
private fun GateLayout(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** The logo on a large tonal plate: the hero of the onboarding and lock screens. */
@Composable
private fun LogoHero() {
    Box(
        Modifier.size(148.dp).clip(RoundedCornerShape(48.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { AegisLogo(104.dp) }
}

@Composable
private fun Bullet(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        IconBadge(icon, size = 46.dp, shape = CookieShape(4, 0.1f))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var understood by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf(BiometricAuth.availability(context)) }
    LifecycleResumeEffect(Unit) {
        availability = BiometricAuth.availability(context)
        onPauseOrDispose { }
    }
    BackHandler(enabled = page == 1) { page = 0 }

    AnimatedContent(targetState = page, label = "onboarding") { p ->
        GateLayout {
            if (p == 0) {
                Spacer(Modifier.height(40.dp))
                LogoHero()
                Spacer(Modifier.height(24.dp))
                Text("AEGIS", style = MaterialTheme.typography.displayMedium, letterSpacing = 4.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your credentials belong to you.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(36.dp))
                Bullet(Icons.Rounded.NoAccounts, "No account", "Nothing to sign up for.")
                Bullet(Icons.Rounded.CloudOff, "No cloud", "No internet permission. Data stays on this phone.")
                Bullet(Icons.Rounded.Fingerprint, "Fingerprint sealed", "The key only works after a real match.")
                Spacer(Modifier.height(36.dp))
                PrimaryButton("Get started", onClick = { page = 1 })
            } else {
                Spacer(Modifier.height(24.dp))
                Icon(Icons.Rounded.Warning, null, tint = AegisColors.Warning, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(16.dp))
                Text("Before you start", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Bullet(Icons.Rounded.Fingerprint, "Changing fingerprints destroys the key", "Adding one, removing all, or resetting the phone locks this vault forever.")
                Bullet(Icons.Rounded.NoAccounts, "No recovery", "No reset, no recovery email. Nobody can open a lost vault.")
                Bullet(Icons.Rounded.Backup, "Back up", "An encrypted backup is the only way back. Forget its password and it's gone too.")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand AEGIS can't recover my vault.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                if (availability != BiometricAuth.Availability.READY) {
                    AegisCard(Modifier.fillMaxWidth()) {
                        Text(
                            when (availability) {
                                BiometricAuth.Availability.NONE_ENROLLED -> "Add a fingerprint in system settings first."
                                BiometricAuth.Availability.NO_HARDWARE -> "This phone has no strong fingerprint sensor, so a vault can't be created."
                                else -> "Fingerprint unlock isn't available right now."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (availability == BiometricAuth.Availability.NONE_ENROLLED) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                val intent = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                                else Intent(Settings.ACTION_SECURITY_SETTINGS)
                                (activity as? MainActivity)?.openSystemScreen(intent)
                            }) { Text("Open fingerprint settings") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                }
                PrimaryButton(
                    "Create vault with fingerprint",
                    icon = Icons.Rounded.Fingerprint,
                    enabled = understood && !busy && availability == BiometricAuth.Availability.READY,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val r = app.vault.create(activity)) {
                                is AuthOutcome.Ok -> r.keys.wipe()
                                is AuthOutcome.Fail -> error = r.message
                            }
                            busy = false
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { (activity as? MainActivity)?.startImport() }) { Text("Restore from backup") }
            }
        }
    }
}

@Composable
fun LockScreen() {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var autoTried by remember { mutableStateOf(false) }
    val unlock = {
        if (!busy) {
            busy = true
            scope.launch {
                error = app.vault.unlock(activity)
                busy = false
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        // Prompt automatically when returning to the app, but not right after an Emergency Lock.
        if (!autoTried) {
            autoTried = true
            if (!app.vault.suppressAutoPrompt) unlock()
        }
        onPauseOrDispose { }
    }
    GateLayout {
        Spacer(Modifier.height(80.dp))
        LogoHero()
        Spacer(Modifier.height(24.dp))
        Text("AEGIS", style = MaterialTheme.typography.displayMedium, letterSpacing = 4.sp)
        Spacer(Modifier.height(8.dp))
        Text("Vault locked", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton("Unlock", icon = Icons.Rounded.Fingerprint, enabled = !busy, onClick = { unlock() })
    }
}

@Composable
fun InvalidatedScreen() {
    val context = LocalContext.current
    val app = context.aegis
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var confirmErase by remember { mutableStateOf(false) }
    GateLayout {
        Spacer(Modifier.height(56.dp))
        Icon(Icons.Rounded.GppBad, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text("This vault can't be opened", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(
            "Your fingerprints changed, so Android destroyed the vault key. The data can't be decrypted any more. Only a backup can bring it back.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Restore from backup", icon = Icons.Rounded.Restore, onClick = { (activity as? MainActivity)?.startImport() })
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { confirmErase = true }, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Erase and start empty") }
    }
    if (confirmErase) {
        ConfirmDialog(
            title = "Erase the inaccessible vault?",
            text = "Every entry on this phone is deleted for good.",
            confirm = "Erase", danger = true,
            onConfirm = {
                confirmErase = false
                scope.launch { app.vault.erase() }
            },
            onDismiss = { confirmErase = false },
        )
    }
}
