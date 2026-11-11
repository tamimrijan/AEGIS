package com.aegis.vault.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.aegis.vault.R
import com.aegis.vault.aegis
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.Vault
import com.aegis.vault.logic.Matcher
import com.aegis.vault.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * System-wide autofill. The service itself never decrypts a password: every suggestion is an
 * "authenticated dataset" that opens [AutofillAuthActivity], where a biometric Keystore
 * operation releases the one credential the user picked.
 */
class AegisAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            val form = structure?.let(StructureParser::parse)
            val vault = aegis.vault
            val usable = vault.state.value == Vault.State.LOCKED || vault.state.value == Vault.State.UNLOCKED
            if (form == null || !form.fillable || form.packageName == packageName || !usable) {
                callback.onSuccess(null)
                return
            }

            val specs: List<InlinePresentationSpec> =
                if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty() else emptyList()
            val maxInline = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0 else 0

            val response = FillResponse.Builder()
            var index = 0
            fun add(title: String, subtitle: String, itemId: String?) {
                val spec = if (index < maxInline && specs.isNotEmpty()) specs[minOf(index, specs.size - 1)] else null
                response.addDataset(dataset(form, title, subtitle, itemId, spec, index))
                index++
            }

            // Names of matching accounts are only known while the vault is unlocked; when it is
            // locked a single entry leads to biometric unlock followed by matching.
            if (vault.isUnlocked) {
                val logins = vault.items.value.filter { it.type == ItemType.LOGIN }
                Matcher.match(logins, form.packageName, form.webDomain).take(4).forEach {
                    add(it.title.ifBlank { "Login" }, it.username.ifBlank { "No username" }, it.id)
                }
                add("Search AEGIS vault", "Choose another login", null)
            } else {
                add("Fill with AEGIS", "Unlock with fingerprint", null)
            }

            form.passwordId?.let { pwId ->
                var types = SaveInfo.SAVE_DATA_TYPE_PASSWORD
                if (form.usernameId != null) types = types or SaveInfo.SAVE_DATA_TYPE_USERNAME
                val save = SaveInfo.Builder(types, arrayOf(pwId))
                    .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                form.usernameId?.let { save.setOptionalIds(arrayOf(it)) }
                response.setSaveInfo(save.build())
            }
            callback.onSuccess(response.build())
        } catch (e: Exception) {
            callback.onFailure("AEGIS could not read this form")
        }
    }

    private fun dataset(
        form: ParsedForm, title: String, subtitle: String, itemId: String?,
        inlineSpec: InlinePresentationSpec?, index: Int,
    ): Dataset {
        val views = AutofillUi.remoteViews(this, title, subtitle)
        @Suppress("DEPRECATION")
        val builder = Dataset.Builder(views)
        val inline = if (Build.VERSION.SDK_INT >= 30 && inlineSpec != null) {
            AutofillUi.inline(this, inlineSpec, title, subtitle)
        } else null
        for (id in listOfNotNull(form.usernameId, form.passwordId)) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30 && inline != null) builder.setValue(id, null, views, inline)
            else builder.setValue(id, null, views)
        }
        val intent = Intent(this, AutofillAuthActivity::class.java).putExtra(AutofillAuthActivity.EXTRA_ITEM_ID, itemId)
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE // the system adds the AssistStructure
        builder.setAuthentication(PendingIntent.getActivity(this, 1000 + index, intent, flags).intentSender)
        return builder.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val form = request.fillContexts.lastOrNull()?.structure?.let(StructureParser::parse)
            val state = aegis.vault.state.value
            if (form == null || form.passwordValue.isBlank() || form.packageName == packageName ||
                (state != Vault.State.LOCKED && state != Vault.State.UNLOCKED)
            ) {
                callback.onSuccess()
                return
            }
            // The password stays inside this process: only a random token crosses the Intent.
            val token = PendingSaves.put(
                PendingSaves.Data(form.packageName, form.webDomain, form.usernameValue.trim(), form.passwordValue)
            )
            val intent = Intent(this, AutofillSaveActivity::class.java)
                .putExtra(AutofillSaveActivity.EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 28) {
                val pi = PendingIntent.getActivity(
                    this, 2000, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                callback.onSuccess(pi.intentSender)
            } else {
                startActivity(intent)
                callback.onSuccess()
            }
        } catch (e: Exception) {
            callback.onFailure("AEGIS could not save this login")
        }
    }
}

/** In-memory hand-off of a just-submitted login to the confirmation screen. */
object PendingSaves {
    class Data(val packageName: String, val webDomain: String?, val username: String, val password: String)

    private val map = ConcurrentHashMap<String, Data>()

    fun put(data: Data): String {
        map.clear() // only the most recent submission is ever relevant
        return UUID.randomUUID().toString().also { map[it] = data }
    }

    fun take(token: String?): Data? = token?.let { map.remove(it) }
}

object AutofillUi {
    fun remoteViews(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    /** Keyboard (inline) suggestion chip for Android 11+ keyboards such as Gboard and Samsung Keyboard. */
    @RequiresApi(30)
    @android.annotation.SuppressLint("RestrictedApi") // .slice is the documented way to build an InlinePresentation
    fun inline(context: Context, spec: InlinePresentationSpec, title: String, subtitle: String): InlinePresentation? {
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(
            context, 3000, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val slice = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setStartIcon(Icon.createWithResource(context, R.drawable.ic_tile))
            .setContentDescription("$title, $subtitle")
            .build().slice
        return InlinePresentation(slice, spec, false)
    }
}
