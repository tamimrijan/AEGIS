package com.aegis.vault.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns the key-encryption key (KEK) inside Android Keystore.
 *
 * The KEK is AES-256-GCM, non-exportable, requires a strong biometric for EVERY use
 * (no validity window) and is invalidated by the OS when biometric enrollment changes.
 * It is only ever used through a BiometricPrompt CryptoObject.
 */
object KeystoreManager {
    private const val ALIAS = "aegis_vault_kek_v1"
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /** Creates a fresh KEK, preferring StrongBox and falling back to the TEE. */
    fun createKey(context: Context) {
        deleteKey()
        val strongBox = Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                generate(true)
                return
            } catch (e: Exception) {
                deleteKey() // StrongBox present but unusable for this spec: fall back to TEE
            }
        }
        generate(false)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= 28 && strongBox) spec.setIsStrongBoxBacked(true)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
            init(spec.build())
            generateKey()
        }
    }

    private fun key(): SecretKey =
        keyStore().getKey(ALIAS, null) as? SecretKey
            ?: throw KeyPermanentlyInvalidatedException("Vault key is no longer present in Keystore")

    /** Throws [KeyPermanentlyInvalidatedException] if biometric enrollment changed. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }

    /** Throws [KeyPermanentlyInvalidatedException] if biometric enrollment changed. */
    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }

    /** Human-readable description of where the KEK lives. */
    fun securityLevel(): String = runCatching {
        val k = key()
        val info = SecretKeyFactory.getInstance(k.algorithm, STORE)
            .getKeySpec(k, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox secure element"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted Execution Environment"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software only (no secure hardware)"
                else -> "Secure hardware"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "Secure hardware (TEE / StrongBox)"
            else "Software only (no secure hardware)"
        }
    }.getOrDefault("Unknown")
}
