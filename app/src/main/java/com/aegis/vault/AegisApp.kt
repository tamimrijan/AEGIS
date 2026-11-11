package com.aegis.vault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aegis.vault.data.Vault
import kotlinx.coroutines.flow.MutableStateFlow

class AegisApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var vault: Vault
        private set
    lateinit var autoLock: AutoLock
        private set
    lateinit var clipboard: ClipboardGuard
        private set
    val io = BackupIo()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        vault = Vault(this)
        clipboard = ClipboardGuard(this, prefs)
        autoLock = AutoLock(this, vault, prefs).also { it.install() }
    }
}

val Context.aegis: AegisApp get() = applicationContext as AegisApp

/** Hand-off between the document picker (owned by MainActivity) and the backup UI. */
class BackupIo {
    /** Already-encrypted backup bytes waiting for the user to choose a destination. */
    @Volatile
    var pendingExport: ByteArray? = null

    /** Raw bytes of a backup file the user picked for restore (still encrypted). */
    val imported = MutableStateFlow<ByteArray?>(null)
}

/** Non-sensitive preferences only. Nothing secret is ever written here. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("aegis_settings", Context.MODE_PRIVATE)

    val autoLockSeconds = MutableStateFlow(sp.getInt("autoLock", 0))
    val clipboardSeconds = MutableStateFlow(sp.getInt("clipboard", 30))
    val theme = MutableStateFlow(sp.getString("theme", "dark") ?: "dark")
    val blockScreenshots = MutableStateFlow(sp.getBoolean("blockScreenshots", true))
    val dynamicColor = MutableStateFlow(sp.getBoolean("dynamicColor", false))

    fun setDynamicColor(on: Boolean) {
        sp.edit().putBoolean("dynamicColor", on).apply(); dynamicColor.value = on
    }

    fun setAutoLock(seconds: Int) {
        sp.edit().putInt("autoLock", seconds).apply(); autoLockSeconds.value = seconds
    }

    fun setClipboard(seconds: Int) {
        sp.edit().putInt("clipboard", seconds).apply(); clipboardSeconds.value = seconds
    }

    fun setTheme(mode: String) {
        sp.edit().putString("theme", mode).apply(); theme.value = mode
    }

    fun setBlockScreenshots(block: Boolean) {
        sp.edit().putBoolean("blockScreenshots", block).apply(); blockScreenshots.value = block
    }

    companion object {
        val AUTO_LOCK_OPTIONS = listOf(0 to "Immediately", 15 to "15 seconds", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes")
        val CLIPBOARD_OPTIONS = listOf(15 to "15 seconds", 30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes")
    }
}

/**
 * Locks the vault:
 *  - the instant the screen turns off (always);
 *  - when the app leaves the foreground, after the configured delay ("Immediately" = at once);
 *  - after the same delay without any touch while the app is open ("Immediately" uses 60 s here,
 *    since a zero-second idle timeout would make the app unusable).
 */
class AutoLock(
    private val app: Application,
    private val vault: Vault,
    private val prefs: Prefs,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var holdUntil = 0L
    private var foreground = false
    private val lockNow = Runnable { vault.lock(manual = foreground) }

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    holdUntil = 0
                    handler.removeCallbacks(lockNow)
                    vault.lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Call right before AEGIS itself opens a system screen (file picker, settings) so that
     * returning from it does not find the vault locked. Screen-off still locks immediately.
     */
    fun hold(millis: Long = 120_000) {
        holdUntil = SystemClock.elapsedRealtime() + millis
    }

    /** Any user interaction restarts the idle countdown. */
    fun touch() {
        if (!foreground) return
        handler.removeCallbacks(lockNow)
        val seconds = prefs.autoLockSeconds.value.let { if (it == 0) 60 else it }
        handler.postDelayed(lockNow, seconds * 1000L)
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        holdUntil = 0
        touch()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        handler.removeCallbacks(lockNow)
        val held = holdUntil - SystemClock.elapsedRealtime()
        val delay = maxOf(prefs.autoLockSeconds.value * 1000L, held)
        if (delay <= 0) vault.lock() else handler.postDelayed(lockNow, delay)
    }
}

/** Copies values flagged as sensitive and wipes the clipboard after the configured delay. */
class ClipboardGuard(private val app: Context, private val prefs: Prefs) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var last: String? = null
    private val clear = Runnable { clearNow() }

    fun copy(value: String, sensitive: Boolean = true) {
        val clip = ClipData.newPlainText("AEGIS", value)
        if (sensitive) {
            // Hides the value from the Android 13+ clipboard preview and from keyboard clip strips.
            clip.description.extras = PersistableBundle().apply {
                val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE"
                putBoolean(key, true)
            }
        }
        manager.setPrimaryClip(clip)
        handler.removeCallbacks(clear)
        if (sensitive) {
            last = value
            handler.postDelayed(clear, prefs.clipboardSeconds.value * 1000L)
        }
    }

    private fun clearNow() {
        val expected = last ?: return
        last = null
        runCatching {
            // In the foreground we can verify the clipboard still holds our value; in the
            // background Android 10+ hides it from us, so we clear unconditionally.
            val current = runCatching { manager.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (current != null && current != expected) return
            if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
            else manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
