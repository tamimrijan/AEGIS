package com.aegis.vault.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a fresh random 96-bit nonce per message.
 * Blob layout: nonce(12) || ciphertext || tag(16).
 * The AAD binds a blob to its record (id, type, field) so blobs cannot be swapped between rows.
 */
object AesGcm {
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Throws javax.crypto.AEADBadTagException when the blob, key or AAD does not match. */
    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        require(blob.size > NONCE_LEN + TAG_BITS / 8 - 1) { "Blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, NONCE_LEN)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
    }
}

/**
 * The two vault data keys.
 *  - [meta] encrypts searchable metadata (titles, usernames, URLs) and TOTP seeds. It lives in
 *    memory only while the vault is unlocked.
 *  - [secret] encrypts passwords, notes, cards and identity details. It is never kept: every
 *    reveal / copy / edit / autofill re-derives it through a fresh biometric Keystore operation.
 */
class VaultKeys(val meta: ByteArray, val secret: ByteArray) {
    fun toBytes(): ByteArray = meta + secret

    fun wipe() {
        meta.fill(0)
        secret.fill(0)
    }

    companion object {
        fun generate(): VaultKeys {
            val r = SecureRandom()
            return VaultKeys(ByteArray(32).also(r::nextBytes), ByteArray(32).also(r::nextBytes))
        }

        fun fromBytes(b: ByteArray): VaultKeys {
            require(b.size == 64) { "Unexpected key material length" }
            return VaultKeys(b.copyOfRange(0, 32), b.copyOfRange(32, 64))
        }
    }
}
