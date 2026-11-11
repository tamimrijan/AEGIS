package com.aegis.vault.data

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import com.aegis.vault.crypto.AesGcm
import com.aegis.vault.crypto.BackupItem
import com.aegis.vault.crypto.BiometricAuth
import com.aegis.vault.crypto.Codec
import com.aegis.vault.crypto.KeystoreManager
import com.aegis.vault.crypto.VaultKeys
import com.aegis.vault.logic.Health
import com.aegis.vault.logic.HealthReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

sealed interface AuthOutcome {
    /** The caller owns [keys] and must call [VaultKeys.wipe] when finished. */
    class Ok(val keys: VaultKeys) : AuthOutcome

    /** [message] is null when the user simply cancelled. */
    class Fail(val message: String?) : AuthOutcome
}

/**
 * The vault session and repository.
 *
 * Key hierarchy:
 *   Android Keystore KEK (biometric-bound, per-use auth, non-exportable)
 *     └─ wraps 64 random bytes = metaKey || secretKey   (stored only as ciphertext)
 *
 * Unlocking keeps the metaKey in memory so the list can be shown and searched. The secretKey is
 * never retained: [authorize] must be called (a real biometric Keystore decryption) each time a
 * password or other protected field is read or written.
 */
class Vault(private val app: Context) {
    enum class State { UNINITIALIZED, LOCKED, UNLOCKED, INVALIDATED }

    private val prefs = app.getSharedPreferences("aegis_keystore", Context.MODE_PRIVATE)
    private val dao = AegisDb.get(app).dao()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items

    /** Rows that failed authenticated decryption (tampered or damaged). */
    val corrupted = MutableStateFlow(0)
    val health = MutableStateFlow<HealthReport?>(null)
    val logVersion = MutableStateFlow(0)

    @Volatile
    private var metaKey: ByteArray? = null

    private fun initialState(): State = when {
        !prefs.contains(P_WRAPPED) -> State.UNINITIALIZED
        prefs.getBoolean(P_INVALID, false) || !KeystoreManager.hasKey() -> State.INVALIDATED
        else -> State.LOCKED
    }

    val isUnlocked: Boolean get() = metaKey != null

    // ---------------------------------------------------------------- key lifecycle

    /** Creates a brand-new vault. Only legal when no vault exists. */
    suspend fun create(activity: FragmentActivity): AuthOutcome {
        if (_state.value != State.UNINITIALIZED) return AuthOutcome.Fail("A vault already exists.")
        val cipher: Cipher = try {
            KeystoreManager.createKey(app)
            KeystoreManager.encryptCipher()
        } catch (e: Exception) {
            KeystoreManager.deleteKey()
            return AuthOutcome.Fail(
                "Could not create a biometric-bound key. Make sure a fingerprint is enrolled. (${e.javaClass.simpleName})"
            )
        }
        val result = BiometricAuth.authenticate(
            activity, cipher, "Create your AEGIS vault", "Confirm your fingerprint to seal the vault key"
        )
        if (result !is BiometricAuth.Result.Success) {
            KeystoreManager.deleteKey()
            return AuthOutcome.Fail((result as? BiometricAuth.Result.Error)?.message)
        }
        val keys = VaultKeys.generate()
        val raw = keys.toBytes()
        try {
            val wrapped = result.cipher.doFinal(raw)
            prefs.edit()
                .putString(P_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putString(P_IV, Base64.encodeToString(result.cipher.iv, Base64.NO_WRAP))
                .putBoolean(P_INVALID, false)
                .commit()
        } catch (e: Exception) {
            keys.wipe()
            KeystoreManager.deleteKey()
            return AuthOutcome.Fail("Could not seal the vault key. (${e.javaClass.simpleName})")
        } finally {
            raw.fill(0)
        }
        metaKey = keys.meta.copyOf()
        _items.value = emptyList()
        _state.value = State.UNLOCKED
        log(LogEvent.VAULT_CREATED)
        return AuthOutcome.Ok(keys)
    }

    /**
     * Runs a biometric prompt whose CryptoObject unwraps the vault keys. Works whether the
     * session is locked or unlocked and does not change the session state.
     */
    suspend fun authorize(activity: FragmentActivity, title: String, subtitle: String): AuthOutcome {
        val wrappedB64 = prefs.getString(P_WRAPPED, null)
        val ivB64 = prefs.getString(P_IV, null)
        if (wrappedB64 == null || ivB64 == null) return AuthOutcome.Fail("No vault exists on this device.")
        val cipher = try {
            KeystoreManager.decryptCipher(Base64.decode(ivB64, Base64.NO_WRAP))
        } catch (e: KeyPermanentlyInvalidatedException) {
            markInvalidated()
            return AuthOutcome.Fail(
                "The vault key was permanently invalidated because the device's biometric enrollment changed."
            )
        } catch (e: Exception) {
            return AuthOutcome.Fail("The secure key store is unavailable right now. (${e.javaClass.simpleName})")
        }
        return when (val r = BiometricAuth.authenticate(activity, cipher, title, subtitle)) {
            is BiometricAuth.Result.Success -> {
                var raw: ByteArray? = null
                try {
                    raw = r.cipher.doFinal(Base64.decode(wrappedB64, Base64.NO_WRAP))
                    AuthOutcome.Ok(VaultKeys.fromBytes(raw))
                } catch (e: Exception) {
                    AuthOutcome.Fail("The vault key could not be verified. (${e.javaClass.simpleName})")
                } finally {
                    raw?.fill(0)
                }
            }
            is BiometricAuth.Result.Error -> AuthOutcome.Fail(r.message)
            BiometricAuth.Result.Cancelled -> AuthOutcome.Fail(null)
        }
    }

    /** Unlocks the session. Returns null on success or cancel, an error message otherwise. */
    suspend fun unlock(activity: FragmentActivity): String? {
        return when (val r = authorize(activity, "Unlock AEGIS", "Use your fingerprint to open the vault")) {
            is AuthOutcome.Ok -> {
                val key = r.keys.meta.copyOf()
                r.keys.wipe()
                metaKey = key
                suppressAutoPrompt = false
                _items.value = decryptItems(key)
                _state.value = State.UNLOCKED
                log(LogEvent.UNLOCK)
                null
            }
            is AuthOutcome.Fail -> {
                if (r.message != null) log(LogEvent.UNLOCK_FAILED)
                r.message
            }
        }
    }

    /** True after a lock the user caused while looking at the app: the lock screen then waits for a tap. */
    @Volatile
    var suppressAutoPrompt = false
        private set

    /**
     * Drops the in-memory key and every decrypted value held by the session.
     * [manual] marks an Emergency Lock or an idle timeout while the app is on screen.
     */
    fun lock(manual: Boolean = false) {
        suppressAutoPrompt = manual
        metaKey?.fill(0)
        metaKey = null
        _items.value = emptyList()
        health.value = null
        if (_state.value == State.UNLOCKED) _state.value = State.LOCKED
    }

    private fun markInvalidated() {
        lock()
        prefs.edit().putBoolean(P_INVALID, true).commit()
        _state.value = State.INVALIDATED
    }

    /** Destroys the vault, its key and its history. There is no undo. */
    suspend fun erase() {
        lock()
        withContext(Dispatchers.IO) {
            dao.deleteAllItems()
            dao.clearLogs()
        }
        prefs.edit().clear().commit()
        KeystoreManager.deleteKey()
        corrupted.value = 0
        _state.value = State.UNINITIALIZED
    }

    // ---------------------------------------------------------------- items

    private fun aad(id: String, type: String, part: String) = "$id|$type|$part".toByteArray()

    /** Decrypts the metadata of every row with [key]. Rows failing authentication are skipped. */
    suspend fun decryptItems(key: ByteArray): List<VaultItem> = withContext(Dispatchers.IO) {
        var bad = 0
        val list = dao.all().mapNotNull { e ->
            try {
                val type = ItemType.valueOf(e.type)
                val meta = Codec.decode(AesGcm.decrypt(key, e.meta, aad(e.id, e.type, "meta")))
                VaultItem(e.id, type, meta, e.createdAt, e.updatedAt)
            } catch (ex: Exception) {
                bad++
                null
            }
        }
        corrupted.value = bad
        list.sortedBy { it.title.lowercase() }
    }

    private suspend fun refresh() {
        metaKey?.let { _items.value = decryptItems(it) }
    }

    /**
     * Inserts or updates an entry. When [secret] is null the existing encrypted secret is kept.
     * [touch] = false leaves the modification date alone (e.g. toggling favourite).
     */
    suspend fun upsert(item: VaultItem, secret: Map<String, String>?, keys: VaultKeys, touch: Boolean = true) {
        write(item, secret, keys.meta, keys.secret, touch)
    }

    /** Metadata-only update using the session key (favourite toggle, TOTP accounts). */
    suspend fun updateMeta(item: VaultItem, touch: Boolean = false): Boolean {
        val key = metaKey ?: return false
        write(item, null, key, null, touch)
        return true
    }

    private suspend fun write(
        item: VaultItem, secret: Map<String, String>?, mKey: ByteArray, sKey: ByteArray?, touch: Boolean,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = dao.get(item.id)
        val type = item.type.name
        val metaBlob = AesGcm.encrypt(mKey, Codec.encode(item.meta), aad(item.id, type, "meta"))
        val secretBlob = if (secret != null && sKey != null) {
            AesGcm.encrypt(sKey, Codec.encode(secret), aad(item.id, type, "secret"))
        } else existing?.secret
        val created = existing?.createdAt ?: item.createdAt.takeIf { it > 0 } ?: now
        val updated = if (touch || existing == null) now else existing.updatedAt
        dao.put(ItemEntity(item.id, type, metaBlob, secretBlob, created, updated))
        refresh()
    }

    suspend fun readSecret(id: String, keys: VaultKeys): Map<String, String> = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext emptyMap()
        val blob = e.secret ?: return@withContext emptyMap()
        Codec.decode(AesGcm.decrypt(keys.secret, blob, aad(e.id, e.type, "secret")))
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { dao.delete(id) }
        refresh()
    }

    // ---------------------------------------------------------------- health

    suspend fun runHealthCheck(keys: VaultKeys): HealthReport = withContext(Dispatchers.Default) {
        val logins = decryptItems(keys.meta).filter { it.type == ItemType.LOGIN }
        val entries = logins.map { item ->
            val pw = runCatching { readSecret(item.id, keys)[K.PASSWORD].orEmpty() }.getOrDefault("")
            Health.Entry(
                id = item.id, title = item.title, username = item.username,
                target = item.url.ifBlank { item.title },
                password = pw,
                changedAt = item[K.PW_CHANGED].toLongOrNull() ?: item.updatedAt,
            )
        }
        Health.analyze(entries, System.currentTimeMillis()).also {
            health.value = it
            log(LogEvent.HEALTH_CHECK, key = keys.meta)
        }
    }

    // ---------------------------------------------------------------- backup

    suspend fun exportAll(keys: VaultKeys): List<BackupItem> = withContext(Dispatchers.IO) {
        dao.all().map { e ->
            BackupItem(
                id = e.id, type = e.type,
                meta = Codec.decode(AesGcm.decrypt(keys.meta, e.meta, aad(e.id, e.type, "meta"))),
                secret = e.secret?.let { Codec.decode(AesGcm.decrypt(keys.secret, it, aad(e.id, e.type, "secret"))) }
                    ?: emptyMap(),
                createdAt = e.createdAt, updatedAt = e.updatedAt,
            )
        }
    }

    /** Re-encrypts backup entries under this device's keys. Returns the number imported. */
    suspend fun importAll(list: List<BackupItem>, keys: VaultKeys, replace: Boolean): Int {
        val n = withContext(Dispatchers.IO) {
            if (replace) dao.deleteAllItems()
            var count = 0
            for (b in list) {
                val type = runCatching { ItemType.valueOf(b.type) }.getOrNull() ?: continue
                dao.put(
                    ItemEntity(
                        id = b.id, type = type.name,
                        meta = AesGcm.encrypt(keys.meta, Codec.encode(b.meta), aad(b.id, type.name, "meta")),
                        secret = if (b.secret.isEmpty()) null
                        else AesGcm.encrypt(keys.secret, Codec.encode(b.secret), aad(b.id, type.name, "secret")),
                        createdAt = b.createdAt, updatedAt = b.updatedAt,
                    )
                )
                count++
            }
            count
        }
        health.value = null
        refresh()
        log(LogEvent.BACKUP_RESTORED, "$n entries", keys.meta)
        return n
    }

    // ---------------------------------------------------------------- activity log

    /** Records an event. [detail] must only ever be an entry title or a count, never a secret. */
    suspend fun log(event: LogEvent, detail: String? = null, key: ByteArray? = metaKey) {
        withContext(Dispatchers.IO) {
            val time = System.currentTimeMillis()
            val blob = if (detail != null && key != null) {
                runCatching { AesGcm.encrypt(key, detail.toByteArray(), "log|${event.name}".toByteArray()) }.getOrNull()
            } else null
            runCatching {
                dao.addLog(LogEntity(time = time, event = event.name, detail = blob))
                dao.trimLogs()
            }
        }
        logVersion.value++
    }

    suspend fun readLogs(): List<LogEntry> = withContext(Dispatchers.IO) {
        val key = metaKey
        dao.logs().map { e ->
            val detail = if (e.detail != null && key != null) {
                runCatching { String(AesGcm.decrypt(key, e.detail, "log|${e.event}".toByteArray())) }.getOrNull()
            } else null
            LogEntry(e.time, runCatching { LogEvent.valueOf(e.event) }.getOrNull(), e.event, detail)
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) { dao.clearLogs() }
        log(LogEvent.LOG_CLEARED)
    }

    private companion object {
        const val P_WRAPPED = "wrapped_keys"
        const val P_IV = "wrapped_iv"
        const val P_INVALID = "invalidated"
    }
}
