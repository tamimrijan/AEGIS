<p align="center">
  <img src="docs/banner.svg" alt="AEGIS - offline password manager, secure vault and smart autofill for Android" width="100%">
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4658B8?logo=jetpackcompose&logoColor=white">
  <img alt="Encryption" src="https://img.shields.io/badge/Encryption-AES--256--GCM-1B7A4B">
  <img alt="Network" src="https://img.shields.io/badge/Internet%20permission-none-BA1A1A">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-84D6C3">
</p>

# AEGIS

**Your credentials belong to you. Your data stays on your device.**

AEGIS is a fully offline, account-free, fingerprint-sealed password manager for Android, named
after the divine shield of Greek mythology. It does not request the `INTERNET` permission, so
there is no way for it to send your data anywhere.

## Features

| | |
|---|---|
| **Biometric vault** | The vault key lives in Android Keystore (StrongBox or TEE) and is unusable without a real fingerprint match, enforced through `BiometricPrompt` + `CryptoObject`. |
| **System-wide autofill** | Android Autofill Service with inline keyboard suggestions, multiple accounts per site, manual vault search, and save / update prompts. |
| **Logins, notes, cards, identities** | Everything is AES-256-GCM encrypted before it touches the database. Categories, favourites and search. |
| **Password generator** | Cryptographically secure passwords and memorable passphrases with a live strength meter. |
| **Authenticator** | Built-in TOTP codes (RFC 6238, SHA-1 / SHA-256 / SHA-512) generated offline. |
| **Security dashboard** | Local password health check: weak, reused, duplicate and old passwords, with an overall score. |
| **Auto-lock and Emergency Lock** | Locks on screen-off, on leaving the app, and after a configurable idle time. |
| **Clipboard protection** | Copied secrets are flagged sensitive and wiped after a timer. |
| **Encrypted backups** | Export to a single file protected by a separate backup password (PBKDF2-HMAC-SHA256, 600,000 rounds + AES-256-GCM). |
| **Quick Vault** | A fingerprint-protected picker from a Quick Settings tile or launcher shortcut for forms autofill cannot read. |
| **Activity log** | A local history of unlocks, edits, copies, autofills and backups. Never records secret contents. |
| **Privacy by construction** | No account, no cloud, no analytics, no ads, no crash reporting, screenshots blocked. |

## Design

AEGIS follows Google's **Material 3 Expressive** style: tonal colour containers instead of
outlines, large rounded shapes, controls that morph while pressed, spring motion, and a
navigation bar whose active tab opens into a pill. Type is set in **Google Sans Flex** (with
its roundness axis turned up for headings) and **Google Sans Code** for passwords and codes.
Dark is the primary theme; a light theme and optional Material You wallpaper colours are included.

The logo is *Bloom*: a scalloped Material Expressive badge holding a verified shield. The
other concepts explored along the way are kept in [`branding/`](branding).

<p>
  <img src="branding/logo-5-bloom.svg" width="120" alt="AEGIS logo: Bloom">
</p>

## Project status

| Check | Result |
|---|---|
| Builds (debug and minified release) | Passes |
| Unit tests: AES-GCM, backup format, TOTP RFC 6238 vectors, generator, strength, health, matching | 32 / 32 pass |
| Android lint | 0 errors |
| Release APK permissions | No `INTERNET` permission |
| Verified on physical devices | **Not yet.** Hardware-dependent paths (biometric prompt, Keystore, autofill in other apps, Quick Settings tile) are implemented but still need on-device verification. |

Treat this as a pre-release until the [device checklist](#device-test-checklist) has been run.

## Security architecture

```
Android Keystore key (AES-256-GCM, non-exportable, StrongBox if available, else TEE)
  · requires a Class 3 biometric for EVERY use (no time window)
  · invalidated by the OS when biometric enrollment changes
        │  used only through BiometricPrompt.CryptoObject
        ▼
wrapped blob in app storage = Enc(metaKey ‖ secretKey)      (64 random bytes)
        │
        ├─ metaKey   → names, usernames, URLs, categories, TOTP seeds, log details
        │              kept in memory only while the vault is unlocked
        └─ secretKey → passwords, notes, cards, identity documents
                       never kept: re-derived by a new fingerprint for each
                       reveal / copy / edit / save / autofill / health check / export
```

- The fingerprint dialog is not a gate in front of readable data. Without a successful match
  the secure hardware refuses to decrypt the wrapped keys, so there is nothing to bypass in app code.
- Every field is AES-256-GCM with a fresh random nonce. Associated data binds each blob to its
  row id, type and field, so rows cannot be swapped into each other. Rows that fail
  authentication are reported instead of shown.
- SQLite holds ciphertext plus timestamps. Preferences hold settings and the wrapped key blob.
- Locking zeroes the in-memory key, empties the decrypted list and removes the unlocked UI.
- **Zero recovery, stated honestly:** adding a fingerprint, removing all fingerprints or
  resetting the phone makes Android destroy the key. AEGIS then offers exactly two options —
  restore from your encrypted backup, or erase and start empty. It never silently creates a
  replacement key over old data.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Build

Requirements: JDK 17 and the Android SDK (platform 35). Android Studio Ladybug or newer opens
the folder directly.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:assembleRelease
```

```bash
./gradlew :app:testDebugUnitTest
```

On Windows use `gradlew.bat`. APKs are written to `app/build/outputs/apk/`. Create a
`local.properties` file containing `sdk.dir=<path to your Android SDK>` if Android Studio does
not do it for you.

### Release signing

Without configuration, release builds are signed with the Android debug key so they can be
sideloaded; that is not suitable for distribution. For a real release, create your own keystore
and a `keystore.properties` file in the project root (both are git-ignored):

```
storeFile=aegis-release.jks
storePassword=...
keyAlias=aegis
keyPassword=...
```

## Install and set up

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

1. **Fingerprint.** Enrol at least one fingerprint in Android settings, open AEGIS, read the
   onboarding pages, and tap **Create vault with fingerprint**.
2. **Autofill.** *Settings → System autofill service* in AEGIS, then choose AEGIS in the Android
   picker. In Chrome also enable *Settings → Autofill services → Autofill using another service*.
3. **Backup.** *Settings → Create encrypted backup*, choose a backup password of 10+ characters,
   and store the `.aegis` file off the phone. **A forgotten backup password cannot be recovered.**
4. **Restore.** *Settings → Restore from backup* (also offered on first launch), enter the backup
   password, review the verified contents, choose *Merge* or *Replace*, confirm with your fingerprint.
5. **Quick Vault.** Add the *Quick Vault* tile to Quick Settings, or long-press the app icon.

Autofill matching: in known browsers AEGIS matches the page's registrable domain against the
saved URL. In other apps it only offers logins explicitly linked to that app's package, so an
unknown app that merely claims to show `google.com` is never given Google credentials.

## Project structure

```
app/src/main/java/com/aegis/vault/
  AegisApp.kt          Application, preferences, auto-lock, clipboard guard
  crypto/              AES-GCM, Keystore key, BiometricPrompt wrapper, backup format
  data/                Room database (ciphertext only), models, vault session + repository
  logic/               Generator, word list, TOTP, health analysis, autofill matching
  autofill/            AutofillService, form parser, fill / save screens
  quick/               Quick Vault sheet and Quick Settings tile
  ui/                  Compose UI: theme, navigation, vault, generator, authenticator,
                       security dashboard, settings, backup dialogs, about
app/src/test/          Unit tests
branding/              Logo concepts (SVG)
licenses/              Font licences (SIL OFL 1.1)
```

The developer details on the About tab come from `AboutInfo` at the top of
`app/src/main/java/com/aegis/vault/ui/AboutScreen.kt`; the photo is
`app/src/main/res/drawable-nodpi/developer_photo.jpg`.

## Design decisions worth knowing

| Topic | Decision | Reason |
|---|---|---|
| Website favicons | App icons for linked apps, coloured monogram otherwise | Favicons need network access, which the app does not have. |
| Fingerprint on save | Adding or saving an entry also needs a fingerprint | The key for secrets is never held in memory. |
| Autofill while locked | One "Fill with AEGIS" entry, then fingerprint, then matching accounts | Account names are encrypted; listing them while locked would mean storing them unprotected. |
| Backup key derivation | PBKDF2-HMAC-SHA256, 600,000 iterations | Avoids shipping a native Argon2 library. |
| TOTP entry | Setup key or pasted `otpauth://` link | A QR scanner would add a camera permission and a scanning library. |
| Material 3 Expressive | Built by hand on the stable Material 3 library | The official Expressive components were still alpha when this was written. |

## Known limitations

- Decrypted text on screen is held as JVM strings, which cannot be reliably overwritten; keys are
  byte arrays and are zeroed. A rooted or compromised device defeats any app-level protection.
- Clipboard auto-clear is a timer in the app process; if Android kills the process first, the
  value stays until Android's own clipboard expiry.
- Apps that draw their own text fields expose nothing to any autofill service. Use Quick Vault there.
- Domain matching uses a short built-in list of two-part suffixes, not the full Public Suffix List.
- App identity is the package name; signing-certificate checks and Digital Asset Links are not implemented.
- Replacing the whole database with an older copy of itself (rollback) is not detected.
- The passphrase list has 554 words (about 9.1 bits per word); use six or more words for important accounts.
- Face or iris unlock works only where the device reports it as a Class 3 (strong) biometric.

## Device test checklist

1. Onboarding refuses to create a vault with no fingerprint enrolled, and creates one after enrolment.
2. Force-stop and reopen: the vault is locked and opens only with a fingerprint.
3. Add, edit, favourite, search and delete a login, note, card and identity.
4. Reveal and copy both prompt for a fingerprint; the clipboard clears after the configured time.
5. Emergency Lock returns to the lock screen and does not re-prompt by itself.
6. Each auto-lock setting; turning the screen off always locks.
7. The recents screen and screenshots show nothing.
8. Autofill in a native app, in Chrome and in Samsung Internet; multiple accounts for one site; save prompt after a sign-up.
9. Authenticator codes match another authenticator for the same key.
10. The security check finds a deliberately weak and a reused password.
11. Backup → erase vault → restore; a wrong backup password is rejected.
12. Add a second fingerprint in Android settings: AEGIS must show "This vault can no longer be opened" and restore from the backup.

## Author

**Tamim Ahmed Rijan** · [GitHub](https://github.com/tamimrijan) · [Instagram](https://www.instagram.com/tamim_ahmed_rijan/)

## License

Released under the [MIT License](LICENSE).

## Credits

Typefaces: [Google Sans Flex](https://github.com/googlefonts/googlesans-flex) and Google Sans
Code, SIL Open Font License 1.1 (see [`licenses/`](licenses)). Built with Kotlin, Jetpack
Compose, Material 3, Room and AndroidX (Apache License 2.0).

---

<p align="center"><b>No cloud. No registration. No recovery service. No unnecessary data collection.</b><br>Just privacy, security, and complete ownership of your digital secrets.</p>
