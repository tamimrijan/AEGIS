package com.aegis.vault.logic

import java.security.SecureRandom
import kotlin.math.ln

data class PasswordOptions(
    val length: Int = 20,
    val upper: Boolean = true,
    val lower: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeAmbiguous: Boolean = false,
    val exclude: String = "",
)

data class PassphraseOptions(
    val words: Int = 5,
    val separator: String = "-",
    val capitalize: Boolean = true,
    val includeNumber: Boolean = true,
)

data class Generated(val value: String, val bits: Double)

/** All randomness comes from [SecureRandom]; `nextInt(bound)` is unbiased. */
object PasswordGenerator {
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/~"
    const val AMBIGUOUS = "O0oIl1|"

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** Returns null when the options leave no characters to choose from. */
    fun password(o: PasswordOptions, random: SecureRandom = SecureRandom()): Generated? {
        fun pool(enabled: Boolean, chars: String): String =
            if (!enabled) "" else chars.filter { c ->
                c !in o.exclude && !(o.excludeAmbiguous && c in AMBIGUOUS)
            }

        val pools = listOf(pool(o.upper, UPPER), pool(o.lower, LOWER), pool(o.digits, DIGITS), pool(o.symbols, SYMBOLS))
            .filter { it.isNotEmpty() }
        if (pools.isEmpty()) return null
        val all = pools.joinToString("")
        val length = o.length.coerceIn(4, 128)
        val out = CharArray(length)
        var n = 0
        // Guarantee one character from every selected class, then fill from the full alphabet.
        if (length >= pools.size) for (p in pools) out[n++] = p[random.nextInt(p.length)]
        while (n < length) out[n++] = all[random.nextInt(all.length)]
        for (i in length - 1 downTo 1) { // Fisher–Yates
            val j = random.nextInt(i + 1)
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return Generated(String(out), length * log2(all.length.toDouble()))
    }

    fun passphrase(o: PassphraseOptions, random: SecureRandom = SecureRandom()): Generated {
        val list = Wordlist.words
        val count = o.words.coerceIn(3, 12)
        val picked = MutableList(count) { list[random.nextInt(list.size)] }
            .map { w -> if (o.capitalize) w.replaceFirstChar { it.uppercase() } else w }
            .toMutableList()
        var bits = count * log2(list.size.toDouble())
        if (o.includeNumber) {
            val at = random.nextInt(count)
            picked[at] = picked[at] + random.nextInt(10)
            bits += log2(10.0) + log2(count.toDouble())
        }
        return Generated(picked.joinToString(o.separator), bits)
    }
}

data class Strength(val score: Int, val bits: Double) {
    val label: String
        get() = when (score) {
            0 -> "Very weak"; 1 -> "Weak"; 2 -> "Fair"; 3 -> "Strong"; else -> "Excellent"
        }
}

/**
 * Local, offline strength estimate. Starts from alphabet-size entropy and discounts repeated
 * and sequential characters, keyboard walks and well-known passwords. It is a heuristic.
 */
object StrengthMeter {
    private val COMMON = setOf(
        "password", "123456", "12345678", "123456789", "1234567890", "qwerty", "abc123", "111111",
        "123123", "letmein", "welcome", "admin", "iloveyou", "monkey", "dragon", "football",
        "baseball", "sunshine", "princess", "master", "shadow", "superman", "michael", "passw0rd",
        "password1", "qwerty123", "000000", "654321", "trustno1", "login", "starwars", "whatever",
        "qazwsx", "zaq12wsx", "1q2w3e4r", "1qaz2wsx", "asdfgh", "asdfghjkl", "zxcvbnm", "freedom",
        "hello", "secret", "changeme", "default", "root", "toor", "test", "guest", "p@ssw0rd",
    )
    private val WALKS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890", "abcdefghijklmnopqrstuvwxyz")

    fun estimate(pw: String): Strength {
        if (pw.isEmpty()) return Strength(0, 0.0)
        val lower = pw.lowercase()
        if (lower in COMMON || lower.trimEnd { it.isDigit() || it == '!' } in COMMON) return Strength(0, 8.0)

        var pool = 0
        if (pw.any { it in 'a'..'z' }) pool += 26
        if (pw.any { it in 'A'..'Z' }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 32
        if (pool == 0) pool = 26

        var effective = 0.0
        for (i in pw.indices) {
            val weak = i > 0 && (pw[i] == pw[i - 1] || kotlin.math.abs(pw[i].code - pw[i - 1].code) == 1 || onWalk(lower, i))
            effective += if (weak) 0.25 else 1.0
        }
        if (pw.toSet().size * 2 < pw.length) effective *= 0.75

        val bits = effective * (ln(pool.toDouble()) / ln(2.0))
        val score = when {
            bits < 30 -> 0
            bits < 45 -> 1
            bits < 60 -> 2
            bits < 80 -> 3
            else -> 4
        }
        return Strength(score, bits)
    }

    private fun onWalk(lower: String, i: Int): Boolean {
        val pair = lower.substring(i - 1, i + 1)
        return WALKS.any { it.contains(pair) || it.reversed().contains(pair) }
    }
}
