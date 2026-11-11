package com.aegis.vault.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.aegis.vault.data.Vault
import com.aegis.vault.data.VaultItem
import com.aegis.vault.ui.AegisTheme
import com.aegis.vault.ui.CredentialPicker
import com.aegis.vault.ui.MainActivity
import com.aegis.vault.ui.SecureActivity
import com.aegis.vault.ui.SheetHeader
import com.aegis.vault.ui.SheetScaffold
import com.aegis.vault.ui.toast
import kotlinx.coroutines.launch

/**
 * Lightweight credential selector for when autofill cannot recognise a form. Opens over the
 * current app from the Quick Settings tile or the launcher shortcut: fingerprint, pick, copy.
 */
class QuickVaultActivity : SecureActivity() {
    private var keys: VaultKeys? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val vault = aegis.vault
        val state = vault.state.value
        if (state != Vault.State.LOCKED && state != Vault.State.UNLOCKED) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        var logins by mutableStateOf<List<VaultItem>?>(null)
        var selected by mutableStateOf<String?>(null)

        fun copyPassword(item: VaultItem) {
            val k = keys ?: return
            lifecycleScope.launch {
                runCatching { vault.readSecret(item.id, k)[K.PASSWORD].orEmpty() }
                    .onSuccess { pw ->
                        aegis.clipboard.copy(pw)
                        vault.log(LogEvent.SECRET_COPIED, item.title, k.meta)
                        toast("Password copied · clears in ${aegis.prefs.clipboardSeconds.value}s")
                    }
                    .onFailure { toast("This entry could not be decrypted") }
            }
        }

        setContent {
            AegisTheme(aegis.prefs.theme.collectAsState().value) {
                logins?.let { list ->
                    SheetScaffold(onDismiss = { finish() }) {
                        SheetHeader("Quick Vault", "Tap a login, then copy what you need")
                        Spacer(Modifier.height(16.dp))
                        CredentialPicker(
                            suggested = emptyList(), all = list, selectedId = selected,
                            selectedContent = { item ->
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            aegis.clipboard.copy(item.username, sensitive = false)
                                            toast("Username copied")
                                        },
                                        enabled = item.username.isNotBlank(), modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.Person, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp)); Text("Username")
                                    }
                                    Button(onClick = { copyPassword(item) }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp)); Text("Password")
                                    }
                                }
                            },
                            onPick = { selected = if (selected == it.id) null else it.id },
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
            }
        }

        lifecycleScope.launch {
            withResumed { }
            when (val r = vault.authorize(this@QuickVaultActivity, "AEGIS Quick Vault", "Confirm your fingerprint to open your logins")) {
                is AuthOutcome.Fail -> {
                    r.message?.let { toast(it) }
                    finish()
                }
                is AuthOutcome.Ok -> {
                    keys = r.keys
                    logins = vault.decryptItems(r.keys.meta).filter { it.type == ItemType.LOGIN }
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
}

/** Quick Settings tile that opens [QuickVaultActivity] over whatever app is on screen. */
class QuickVaultTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, QuickVaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 4000, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
