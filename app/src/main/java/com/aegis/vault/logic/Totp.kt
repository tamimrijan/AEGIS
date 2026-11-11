package com.aegis.vault.logic

import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpParams(
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
)

/** RFC 6238 time-based one-time passwords (HOTP per RFC 4226), fully offline. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Decodes RFC 4648 Base32, ignoring spaces, dashes, padding and case. */
    fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        require(clean.isNotEmpty()) { "Empty secret" }
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid Base32 character '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        require(out.isNotEmpty()) { "Secret too short" }
        return out.toByteArray()
    }

    fun isValidSecret(input: String): Boolean = runCatching { base32Decode(input) }.isSuccess

    fun code(
        secret: ByteArray,
        timeSeconds: Long,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
    ): String {
        val counter = timeSeconds / period
        val algo = when (algorithm.uppercase()) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(secret, algo))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** Parses `otpauth://totp/Issuer:account?secret=...&issuer=...&digits=6&period=30&algorithm=SHA1`. */
    fun parseUri(uri: String): TotpParams? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("otpauth://totp/", ignoreCase = true)) return null
        val rest = trimmed.substring("otpauth://totp/".length)
        val label = dec(rest.substringBefore('?'))
        val query = rest.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to dec(it.substringAfter('=')) }
        val secret = query["secret"]?.takeIf { isValidSecret(it) } ?: return null
        val labelIssuer = if (label.contains(':')) label.substringBefore(':').trim() else ""
        val account = label.substringAfter(':').trim()
        return TotpParams(
            issuer = query["issuer"]?.takeIf { it.isNotBlank() } ?: labelIssuer.ifBlank { account },
            account = account,
            secret = secret.uppercase().filter { it != ' ' && it != '=' },
            algorithm = query["algorithm"]?.uppercase()?.takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1",
            digits = query["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6,
            period = query["period"]?.toIntOrNull()?.takeIf { it in 10..120 } ?: 30,
        )
    }

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
