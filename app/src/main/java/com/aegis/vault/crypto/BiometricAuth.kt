package com.aegis.vault.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume

object BiometricAuth {
    enum class Availability { READY, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE }

    sealed interface Result {
        /** [cipher] has been authorised by the Keystore for exactly one operation. */
        class Success(val cipher: Cipher) : Result
        class Error(val message: String) : Result
        data object Cancelled : Result
    }

    fun availability(context: Context): Availability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
            else -> Availability.UNAVAILABLE
        }

    /**
     * Shows the system biometric prompt bound to [cipher] through a CryptoObject.
     * The cipher is unusable unless the secure hardware itself verifies the biometric,
     * so a hooked or faked "success" callback cannot decrypt anything.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
    ): Result = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (cont.isActive) {
                        cont.resume(
                            if (c != null) Result.Success(c)
                            else Result.Error("Biometric result carried no cryptographic object")
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    cont.resume(if (cancelled) Result.Cancelled else Result.Error(errString.toString()))
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            try {
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Result.Error(e.message ?: "Biometric prompt failed"))
            }
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
    }
}
