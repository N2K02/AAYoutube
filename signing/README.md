# Signing key

`keys.tar.enc` holds the private signing key, encrypted with AES-256 (OpenSSL, PBKDF2).
The passphrase is the `SIGNING_PASSPHRASE` repository secret (Settings → Secrets and variables → Actions).
It is the same key and passphrase as MRadio. Keep the passphrase safe: without it no update can install over the current app.
