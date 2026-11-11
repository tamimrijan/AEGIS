package com.aegis.vault.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupItem(
    val id: String,
    val type: String,
    val meta: Map<String, String>,
    val secret: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BackupPayload(val createdAt: Long, val items: List<BackupItem>)

class BackupException(message: String) : Exception(message)

/**
 * Encrypted backup file format (version 1), all integers big-endian:
 *
 *   magic "AEGISBK1" (8) | version (1) | createdAt ms (8) | PBKDF2 iterations (4)
 *   | salt (16) | nonce (12) | AES-256-GCM ciphertext + tag
 *
 * Key = PBKDF2-HMAC-SHA256(password, salt, iterations) -> 256 bit.
 * The whole 49-byte header is authenticated as GCM AAD, so the date, iteration count and
 * salt cannot be altered without detection. A wrong password and a corrupted file are
 * indistinguishable by design: both fail tag verification.
 */
object BackupCrypto {
    private val MAGIC = "AEGISBK1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val HEADER_LEN = 8 + 1 + 8 + 4 + 16 + 12
    const val MIN_PASSWORD_LENGTH = 10

    class Header(val createdAt: Long, val iterations: Int, val salt: ByteArray, val nonce: ByteArray)

    fun looksLikeBackup(file: ByteArray): Boolean =
        file.size > HEADER_LEN + 16 && file.copyOfRange(0, 8).contentEquals(MAGIC)

    fun parseHeader(file: ByteArray): Header {
        if (!looksLikeBackup(file)) throw BackupException("This is not an AEGIS backup file.")
        val buf = ByteBuffer.wrap(file, 8, HEADER_LEN - 8)
        val version = buf.get()
        if (version != VERSION) throw BackupException("Unsupported backup version ($version).")
        val createdAt = buf.long
        val iterations = buf.int
        if (iterations !in 10_000..10_000_000) throw BackupException("Backup header is corrupted.")
        val salt = ByteArray(16).also { buf.get(it) }
        val nonce = ByteArray(12).also { buf.get(it) }
        return Header(createdAt, iterations, salt, nonce)
    }

    fun encrypt(
        payload: BackupPayload,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).put(VERSION).putLong(payload.createdAt).putInt(iterations)
            .put(salt).put(nonce).array()
        val key = deriveKey(password, salt, iterations)
        val plain = encodePayload(payload)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
            plain.fill(0)
        }
    }

    /** Verifies integrity and decrypts. Throws [BackupException] on any failure. */
    fun decrypt(file: ByteArray, password: CharArray): BackupPayload {
        val header = parseHeader(file)
        val key = deriveKey(password, header.salt, header.iterations)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.nonce))
            cipher.updateAAD(file, 0, HEADER_LEN)
            cipher.doFinal(file, HEADER_LEN, file.size - HEADER_LEN)
        } catch (e: Exception) {
            throw BackupException("Wrong backup password, or the backup file is damaged.")
        } finally {
            key.fill(0)
        }
        try {
            return decodePayload(plain)
        } catch (e: Exception) {
            throw BackupException("Backup contents could not be read.")
        } finally {
            plain.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encodePayload(p: BackupPayload): ByteArray {
        val arr = JSONArray()
        for (i in p.items) {
            arr.put(
                JSONObject()
                    .put("id", i.id).put("type", i.type)
                    .put("createdAt", i.createdAt).put("updatedAt", i.updatedAt)
                    .put("meta", JSONObject(i.meta as Map<*, *>))
                    .put("secret", JSONObject(i.secret as Map<*, *>))
            )
        }
        return JSONObject().put("app", "AEGIS").put("format", 1)
            .put("createdAt", p.createdAt).put("items", arr)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodePayload(bytes: ByteArray): BackupPayload {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val arr = root.getJSONArray("items")
        val items = ArrayList<BackupItem>(arr.length())
        for (n in 0 until arr.length()) {
            val o = arr.getJSONObject(n)
            items += BackupItem(
                id = o.getString("id"),
                type = o.getString("type"),
                meta = o.getJSONObject("meta").toStringMap(),
                secret = o.getJSONObject("secret").toStringMap(),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
            )
        }
        return BackupPayload(root.getLong("createdAt"), items)
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        m[k] = getString(k)
    }
    return m
}

/** Compact map <-> bytes codec used for every encrypted record. */
object Codec {
    fun encode(map: Map<String, String>): ByteArray =
        JSONObject(map as Map<*, *>).toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Map<String, String> =
        JSONObject(String(bytes, Charsets.UTF_8)).toStringMap()
}
