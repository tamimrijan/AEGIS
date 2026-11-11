# Security policy

AEGIS stores passwords, so security reports are taken seriously.

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Use GitHub's private reporting
instead: on this repository open **Security → Report a vulnerability**. Include:

- what you found and why it matters,
- steps to reproduce (device model, Android version, app version),
- any proof of concept.

## Scope

In scope: anything that lets someone read or modify vault contents without a successful
biometric match, weaknesses in the encryption or backup format, autofill handing a credential
to the wrong app or website, and leaks of secrets through logs, the clipboard, screenshots or
notifications.

Out of scope: attacks that require a rooted device, a compromised operating system, or physical
access to an already unlocked vault.

## Design summary

See the *Security architecture* section of the [README](README.md).
