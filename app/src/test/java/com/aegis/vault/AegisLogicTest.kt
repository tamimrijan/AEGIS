package com.aegis.vault

import com.aegis.vault.crypto.AesGcm
import com.aegis.vault.crypto.BackupCrypto
import com.aegis.vault.crypto.BackupException
import com.aegis.vault.crypto.BackupItem
import com.aegis.vault.crypto.BackupPayload
import com.aegis.vault.crypto.Codec
import com.aegis.vault.crypto.VaultKeys
import com.aegis.vault.data.ItemType
import com.aegis.vault.data.K
import com.aegis.vault.data.VaultItem
import com.aegis.vault.logic.Health
import com.aegis.vault.logic.Matcher
import com.aegis.vault.logic.PassphraseOptions
import com.aegis.vault.logic.PasswordGenerator
import com.aegis.vault.logic.PasswordOptions
import com.aegis.vault.logic.StrengthMeter
import com.aegis.vault.logic.Totp
import com.aegis.vault.logic.Wordlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class AesGcmTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun roundTrip() {
        val blob = AesGcm.encrypt(key, "hunter2".toByteArray(), "id|LOGIN|secret".toByteArray())
        assertEquals("hunter2", String(AesGcm.decrypt(key, blob, "id|LOGIN|secret".toByteArray())))
    }

    @Test
    fun nonceIsFreshEveryTime() {
        val a = AesGcm.encrypt(key, "same".toByteArray(), ByteArray(0))
        val b = AesGcm.encrypt(key, "same".toByteArray(), ByteArray(0))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val blob = AesGcm.encrypt(key, "secret".toByteArray(), "aad".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(key, blob, "aad".toByteArray()) }
    }

    @Test
    fun blobCannotBeMovedToAnotherRecord() {
        val blob = AesGcm.encrypt(key, "secret".toByteArray(), "row-1|LOGIN|secret".toByteArray())
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(key, blob, "row-2|LOGIN|secret".toByteArray()) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val blob = AesGcm.encrypt(key, "secret".toByteArray(), ByteArray(0))
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(ByteArray(32) { 9 }, blob, ByteArray(0)) }
    }

    @Test
    fun vaultKeysSerializeAndWipe() {
        val keys = VaultKeys.generate()
        assertFalse(keys.meta.contentEquals(keys.secret))
        val copy = VaultKeys.fromBytes(keys.toBytes())
        assertArrayEquals(keys.meta, copy.meta)
        assertArrayEquals(keys.secret, copy.secret)
        keys.wipe()
        assertTrue(keys.meta.all { it == 0.toByte() } && keys.secret.all { it == 0.toByte() })
    }

    @Test
    fun codecRoundTripsUnicode() {
        val map = mapOf("title" to "Bank – ব্যাংক", "password" to "p@\"ss\\w0rd\n✓")
        assertEquals(map, Codec.decode(Codec.encode(map)))
    }
}

class BackupCryptoTest {
    private val payload = BackupPayload(
        1_700_000_000_000,
        listOf(
            BackupItem("a", "LOGIN", mapOf("title" to "GitHub", "username" to "octo"), mapOf("password" to "s3cr3t!"), 1, 2),
            BackupItem("b", "TOTP", mapOf("title" to "Mail", "totpSecret" to "JBSWY3DPEHPK3PXP"), emptyMap(), 3, 4),
        ),
    )

    // A low iteration count keeps the unit tests fast; the app uses DEFAULT_ITERATIONS.
    private fun encrypt(pw: String) = BackupCrypto.encrypt(payload, pw.toCharArray(), iterations = 10_000)

    @Test
    fun roundTrip() {
        val file = encrypt("correct horse battery")
        assertTrue(BackupCrypto.looksLikeBackup(file))
        assertEquals(payload.createdAt, BackupCrypto.parseHeader(file).createdAt)
        assertEquals(payload, BackupCrypto.decrypt(file, "correct horse battery".toCharArray()))
    }

    @Test
    fun wrongPasswordFails() {
        val file = encrypt("correct horse battery")
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(file, "wrong password".toCharArray()) }
    }

    @Test
    fun corruptedBodyFails() {
        val file = encrypt("correct horse battery")
        file[file.size - 20] = (file[file.size - 20].toInt() xor 0x40).toByte()
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(file, "correct horse battery".toCharArray()) }
    }

    @Test
    fun tamperedHeaderDateFails() {
        val file = encrypt("correct horse battery")
        file[12] = (file[12].toInt() xor 1).toByte() // inside the createdAt field
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(file, "correct horse battery".toCharArray()) }
    }

    @Test
    fun foreignFileIsRejected() {
        assertThrows(BackupException::class.java) { BackupCrypto.parseHeader(ByteArray(200) { 7 }) }
    }

    @Test
    fun plaintextDoesNotAppearInFile() {
        val text = String(encrypt("correct horse battery"), Charsets.ISO_8859_1)
        assertFalse(text.contains("s3cr3t!"))
        assertFalse(text.contains("GitHub"))
    }

    @Test
    fun defaultIterationCountIsStrong() {
        assertTrue(BackupCrypto.DEFAULT_ITERATIONS >= 600_000)
    }
}

class TotpTest {
    // RFC 6238 Appendix B test vectors (8 digits).
    private val sha1 = "12345678901234567890".toByteArray()
    private val sha256 = "12345678901234567890123456789012".toByteArray()
    private val sha512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    @Test
    fun rfc6238Sha1() {
        assertEquals("94287082", Totp.code(sha1, 59, 30, 8, "SHA1"))
        assertEquals("07081804", Totp.code(sha1, 1111111109, 30, 8, "SHA1"))
        assertEquals("89005924", Totp.code(sha1, 1234567890, 30, 8, "SHA1"))
        assertEquals("65353130", Totp.code(sha1, 20000000000, 30, 8, "SHA1"))
    }

    @Test
    fun rfc6238Sha256AndSha512() {
        assertEquals("46119246", Totp.code(sha256, 59, 30, 8, "SHA256"))
        assertEquals("91819424", Totp.code(sha256, 1234567890, 30, 8, "SHA256"))
        assertEquals("90693936", Totp.code(sha512, 59, 30, 8, "SHA512"))
        assertEquals("93441116", Totp.code(sha512, 1234567890, 30, 8, "SHA512"))
    }

    @Test
    fun sixDigitCodesKeepLeadingZeros() {
        assertEquals("287082", Totp.code(sha1, 59))
        assertEquals("081804", Totp.code(sha1, 1111111109))
    }

    @Test
    fun base32() {
        assertArrayEquals("Hello!".toByteArray() + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), Totp.base32Decode("JBSWY3DPEHPK3PXP"))
        assertArrayEquals(sha1, Totp.base32Decode("gezd gnbv-gy3t qojq GEZDGNBVGY3TQOJQ"))
        assertFalse(Totp.isValidSecret("not base32 !!"))
        assertFalse(Totp.isValidSecret(""))
    }

    @Test
    fun parsesOtpauthUri() {
        val p = Totp.parseUri("otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60")
        assertNotNull(p)
        assertEquals("ACME Co", p!!.issuer)
        assertEquals("john.doe@email.com", p.account)
        assertEquals("SHA256", p.algorithm)
        assertEquals(8, p.digits)
        assertEquals(60, p.period)
        assertNull(Totp.parseUri("https://example.com"))
        assertNull(Totp.parseUri("otpauth://totp/x?secret=***"))
    }
}

class GeneratorTest {
    @Test
    fun respectsLengthAndClasses() {
        repeat(200) {
            val g = PasswordGenerator.password(PasswordOptions(length = 24))!!
            assertEquals(24, g.value.length)
            assertTrue(g.value.any { it in PasswordGenerator.UPPER })
            assertTrue(g.value.any { it in PasswordGenerator.LOWER })
            assertTrue(g.value.any { it in PasswordGenerator.DIGITS })
            assertTrue(g.value.any { it in PasswordGenerator.SYMBOLS })
        }
    }

    @Test
    fun respectsExclusions() {
        repeat(200) {
            val g = PasswordGenerator.password(PasswordOptions(length = 40, symbols = false, excludeAmbiguous = true, exclude = "abcXYZ789"))!!
            assertTrue(g.value.none { it in "abcXYZ789" })
            assertTrue(g.value.none { it in PasswordGenerator.AMBIGUOUS })
            assertTrue(g.value.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun digitsOnly() {
        val g = PasswordGenerator.password(PasswordOptions(length = 12, upper = false, lower = false, symbols = false))!!
        assertTrue(g.value.all { it.isDigit() })
    }

    @Test
    fun nothingSelectedReturnsNull() {
        assertNull(PasswordGenerator.password(PasswordOptions(upper = false, lower = false, digits = false, symbols = false)))
        assertNull(PasswordGenerator.password(PasswordOptions(upper = false, lower = false, symbols = false, exclude = "0123456789")))
    }

    @Test
    fun passwordsDiffer() {
        val set = (1..500).map { PasswordGenerator.password(PasswordOptions(length = 16))!!.value }.toSet()
        assertEquals(500, set.size)
    }

    @Test
    fun passphrase() {
        val g = PasswordGenerator.passphrase(PassphraseOptions(words = 6, separator = ".", capitalize = false, includeNumber = false))
        val words = g.value.split(".")
        assertEquals(6, words.size)
        assertTrue(words.all { it in Wordlist.words })
        assertTrue(g.bits > 50)
    }

    @Test
    fun wordlistIsLargeAndClean() {
        assertTrue(Wordlist.words.size >= 500)
        assertEquals(Wordlist.words.size, Wordlist.words.toSet().size)
        assertTrue(Wordlist.words.all { w -> w.all { it in 'a'..'z' } })
    }

    @Test
    fun strengthOrdering() {
        assertEquals(0, StrengthMeter.estimate("password").score)
        assertEquals(0, StrengthMeter.estimate("Password1!".lowercase()).score)
        assertTrue(StrengthMeter.estimate("123456789012").score <= 1)
        assertTrue(StrengthMeter.estimate("aaaaaaaaaaaaaaaa").score <= 1)
        assertTrue(StrengthMeter.estimate("qwertyuiopasdf").score <= 1)
        assertTrue(StrengthMeter.estimate("T7#qLm9!vRx2@Zp4").score >= 3)
        assertTrue(StrengthMeter.estimate(PasswordGenerator.password(PasswordOptions(length = 24))!!.value).score >= 3)
    }
}

class HealthAndMatcherTest {
    private val now = 1_800_000_000_000
    private val recent = now - 10 * 86_400_000L
    private val ancient = now - 500 * 86_400_000L

    @Test
    fun detectsWeakReusedDuplicateOld() {
        val report = Health.analyze(
            listOf(
                Health.Entry("1", "Mail", "me@x.com", "https://mail.example.com", "T7#qLm9!vRx2@Zp4", recent),
                Health.Entry("2", "Shop", "me", "shop.com", "T7#qLm9!vRx2@Zp4", recent),          // reused
                Health.Entry("3", "Forum", "me", "forum.org", "letmein", recent),                 // weak
                Health.Entry("4", "Bank", "me", "https://bank.co.uk/login", "K9\$wPz3&hNq8*Bv5", ancient), // old
                Health.Entry("5", "Bank copy", "ME", "www.bank.co.uk", "K9\$wPz3&hNq8*Bv5", ancient),      // duplicate
                Health.Entry("6", "Fine", "me", "fine.io", "Xy4!Lp8@Qr2#Wn6%", recent),
            ),
            now,
        )
        assertEquals(6, report.total)
        assertEquals(1, report.weak)
        assertEquals(4, report.reused)
        assertEquals(2, report.duplicate)
        assertEquals(2, report.old)
        assertTrue(report.issues.none { it.itemId == "6" })
        assertTrue(report.score in 1..99)
        assertTrue(report.recommendations.isNotEmpty())
    }

    @Test
    fun healthyVaultScoresHundred() {
        val report = Health.analyze(
            listOf(
                Health.Entry("1", "A", "a", "a.com", "Xy4!Lp8@Qr2#Wn6%", recent),
                Health.Entry("2", "B", "b", "b.com", "T7#qLm9!vRx2@Zp4", recent),
            ),
            now,
        )
        assertEquals(100, report.score)
        assertTrue(report.issues.isEmpty())
        assertEquals(100, Health.analyze(emptyList(), now).score)
    }

    @Test
    fun hostAndRegistrableDomain() {
        assertEquals("accounts.google.com", Matcher.host("https://www.accounts.google.com:443/signin?x=1"))
        assertEquals("example.com", Matcher.host("example.com/path"))
        assertNull(Matcher.host("not a url"))
        assertEquals("google.com", Matcher.registrable("accounts.google.com"))
        assertEquals("bbc.co.uk", Matcher.registrable("news.bbc.co.uk"))
        assertTrue(Matcher.sameSite("https://accounts.google.com", "mail.google.com"))
        assertFalse(Matcher.sameSite("https://google.com", "google.com.evil.io"))
        assertFalse(Matcher.sameSite("https://paypal.com", "paypa1.com"))
    }

    private fun login(id: String, title: String, url: String = "", packages: String = "") =
        VaultItem(id, ItemType.LOGIN, mapOf(K.TITLE to title, K.URL to url, K.PACKAGES to packages), 0, 0)

    @Test
    fun matchingRules() {
        val items = listOf(
            login("g1", "Google personal", "https://accounts.google.com"),
            login("g2", "Google work", "google.com"),
            login("fb", "Facebook", "https://facebook.com", "com.facebook.katana"),
            login("x", "Other", "https://other.org"),
        )
        // Trusted browser: match by registrable domain, several accounts for one site.
        assertEquals(listOf("g1", "g2"), Matcher.match(items, "com.android.chrome", "mail.google.com").map { it.id })
        // Native app: only explicitly linked logins.
        assertEquals(listOf("fb"), Matcher.match(items, "com.facebook.katana", null).map { it.id })
        // An untrusted app claiming a web domain must NOT receive that site's credentials.
        assertTrue(Matcher.match(items, "com.evil.app", "google.com").isEmpty())
        // Loose guesses are suggestions only.
        assertEquals(listOf("fb"), Matcher.suggestions(items, "com.facebook.lite").map { it.id })
        assertTrue(Matcher.suggestions(items, "com.unrelated.game").isEmpty())
    }

    @Test
    fun searchNeverSeesSecrets() {
        val item = login("1", "GitHub", "https://github.com").with(K.USERNAME to "octocat")
        assertTrue(item.matches("git"))
        assertTrue(item.matches("OCTO"))
        assertFalse(item.matches("hunter2"))
        assertNotEquals(item, item.with(K.FAVOURITE to "1"))
    }
}
