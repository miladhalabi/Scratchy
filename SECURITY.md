# Security

Scratchy holds people's private notes, so security reports are taken seriously.

## Reporting a problem

Please **don't open a public issue** for a security problem. Instead, either:

- use GitHub's **[private vulnerability reporting](https://github.com/miladhalabi/Scratchy/security/advisories/new)** for this repository, or
- email **halabimilad@gmail.com** with "Scratchy security" in the subject.

Say what you found, how to reproduce it, and what an attacker could do with it. You'll get an answer within a week. Once a fix is out, you'll be credited in the release notes unless you'd rather not be.

Only the latest release gets security fixes.

## How Scratchy protects notes

- **Sync is end-to-end encrypted.** Each note and image is sealed on the device with AES-256-GCM, using a key derived from the vault password (PBKDF2-HMAC-SHA256, 120,000 rounds, a random salt). Google Drive only stores the sealed files, in the app's hidden folder (`drive.appdata` is the only permission asked for).
- **The relay can't read anything.** It passes sealed changes between an account's devices and stores nothing. An account's room on the relay is a hash of the sync key, so it can't be guessed.
- **Secret notes** are left out of every list, search, the card and AI, and their text is encrypted on the device with a key held by the system (Android Keystore, or the desktop's keyring).
- **No accounts, analytics or ads.** AI features only run when the user asks, with their own OpenRouter key, and only send the note they asked about.

## Known limits

These are known and documented, not bugs to report:

- **Images in secret notes are not encrypted on the phone itself.** They are hidden in the app and stored in its private storage, and they are encrypted in Drive like everything else.
- **The fingerprint unlocks the app; it is not tied to the encryption key.** Someone who can unlock the phone and pass the fingerprint check can open secret notes.
- **A forgotten vault password can't be recovered.** There is no back door, so the synced copy is lost with it.
- **The edge bar and card draw over other apps**, which needs Android's "Display over other apps" permission.
