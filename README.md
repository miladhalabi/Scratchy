# Scratchy

A personal Android quick-note app that lives in a thin bar on the edge of the screen. Tap or swipe the bar and a note card opens **over whatever you are doing** (YouTube, a browser, anything) without leaving that app. Write the idea, close the card, carry on.

Package `com.scratchy.app` · Kotlin · plain Android Views (no Compose, no XML layouts) · minSdk 26 · targetSdk 34 · compileSdk 35

## Features

**Capture**
- Edge bar on the left or right side. Tap or swipe to open the card, long-press and drag to move it.
- Floating note card: draggable, resizable, adjustable opacity and blur, genie open/close animation (speed adjustable).
- Pin the card to keep it on screen while you use the phone normally.
- Title and body, checklists, inline images, paste from the clipboard.
- Screenshot handling: the bar turns green when a screenshot is waiting, and the card asks where it goes (current note, new note, or another note).
- Optional AI title suggestions through OpenRouter (only the note you ask about, only when you tap).
- Own copy/cut/paste menu in the card, because an overlay window cannot show Android's text-selection toolbar.

**Notes app**
- Two-column notes grid, search, pinned notes first.
- Hold a note to delete it, with an undo bar.
- Full-screen editor with autosave.

**Secret notes**
- A vault protected by a password and optional fingerprint.
- Opened by a long, deliberate **pull-down at the top of the notes list**. A normal pull shows nothing; far enough and a lock appears with a haptic click; release to open.
- Secret notes are hidden from every list, search, the card and the screenshot picker. Their text is encrypted on disk with an Android Keystore key. Screenshots and the recents preview are blocked on those screens. AI titles are disabled for them.
- Lock toggle on the card and in the editor.

**Sync (optional)**
- Google Drive hidden app-data folder, one encrypted file per note.
- End-to-end encrypted with a key derived from your vault password. Drive only stores ciphertext.
- Three-way line merge. If two devices change the same lines, the newer edit stays and the other is saved as a note ending in "(conflict)". Nothing is silently lost.
- Near-instant updates (about a second) through a small Cloudflare Worker relay, with Drive as the permanent store and catch-up route.
- Background sync every ~15 minutes, a sync status line on the notes screen, and a vault password change that re-encrypts everything.

## How sync works

```
 device A ──(encrypted note)──► Cloudflare Worker (Durable Object) ──► device B   instant, nothing stored
    │                                                                    ▲
    └──(encrypted note file)──► Google Drive appDataFolder ──────────────┘   permanent, also catches up offline devices
```

- **Key**: PBKDF2-SHA256 (120k iterations) of the vault password with a random salt kept in Drive (`meta.json`). Notes and images are sealed with AES-256-GCM before upload.
- **Layout on Drive**: `meta.json`, `n_<id>.enc` (one per note, deletions stored as markers), `img_<name>` (images).
- **Merge**: each device keeps the last agreed version of every note (`sync_state.json`) as the common ancestor. See `Merge.kt` (pure Kotlin, unit tested). Ordering uses an edit counter (`rev`), not the phone clock.
- **Relay**: the Worker only forwards opaque encrypted messages to connected devices of the same account. The account "room" is a hash of the sync key. It stores nothing from the relay route.
- Devices that are offline catch up from Drive when they open the app, reconnect, or on the 15-minute background run.

## Build and install

Requirements: JDK 17+, Android SDK (platform 35), a device with USB debugging.

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # merge logic tests
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is minified (R8) and signed with the local debug keystore (`~/.config/.android/debug.keystore`), so it upgrades over debug installs and keeps your data. Installing stops the edge bar service; turn it back on from the notes screen.

First run: allow **Display over other apps** and photo access (for screenshots), then switch the edge bar on.

## Setting up sync

Sync is off until you connect it. You need two things, both free.

### 1. Google Drive access

1. In the [Google Cloud Console](https://console.cloud.google.com), create a project and enable the **Google Drive API**.
2. Configure the OAuth consent screen (External) and add your Google account as a **test user**.
3. Create an **OAuth client ID** of type **Android** with package `com.scratchy.app` and the SHA-1 of your signing key:
   ```bash
   keytool -list -v -keystore ~/.config/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
   ```
4. In the app: **Settings → Sync → Connect Google Drive**, enter your vault password, and approve access.
5. While the consent screen is in "Testing", Google expires the permission after about 7 days. **Publish the app** from the consent screen to stop that. The only scope used is `drive.appdata`, which is non-sensitive.

Use the **same vault password on every device**.

### 2. Live relay (Cloudflare Worker)

The relay is optional but gives the near-instant updates. It runs on Cloudflare's free plan.

```bash
cd worker
npx wrangler login
npx wrangler deploy
```

Then set `BASE` in `app/src/main/java/com/scratchy/app/Live.kt` to your Worker URL and rebuild. Without a reachable relay, sync still works through Drive (a few seconds slower).

## Code map

`app/src/main/java/com/scratchy/app/`

| File | Role |
| --- | --- |
| `OverlayService.kt` | Foreground service: edge bar window, the floating card, genie animation, screenshot detection |
| `BarView.kt`, `GenieView.kt` | The bar drawing and the card-into-bar warp animation |
| `NoteEditor.kt` | Title/body editor with autosave, checklists, images, AI titles, copy menu |
| `MainActivity.kt` | Notes grid, search, pull-to-open vault gesture, sync status line |
| `NoteActivity.kt` | Full-screen editor |
| `SettingsActivity.kt`, `SettingsUi.kt` | Settings screens and components |
| `Note.kt` | `Note` model and `NoteStore` (local JSON storage, deletion markers) |
| `Vault.kt`, `VaultGate.kt` | Password hash, fingerprint, Keystore encryption, lock screen |
| `Sync.kt` | Drive sync engine, key handling, password change, instant receive |
| `Merge.kt` | Pure merge logic (three-way line merge, conflict copies) |
| `SyncData.kt` | Note JSON format and per-note sync state |
| `Live.kt` | WebSocket connection to the relay |
| `SyncWorker.kt` | 15-minute background sync |
| `Ai.kt`, `Attachments.kt`, `Screenshots.kt`, `Checklist.kt`, `HoldToDelete.kt`, `Appearance.kt`, `CardNote.kt`, `Ui.kt` | Supporting pieces |

`worker/` holds the Cloudflare Worker and its Durable Object (`src/index.js`).

## Permissions

Display over other apps, foreground service, notifications, photo access (screenshot detection), internet, vibrate, biometrics.

## Privacy and limits

- Notes are stored in the app's private storage. Nothing leaves the phone unless you turn on sync or ask for an AI title.
- Images attached to secret notes are hidden in the UI but stored as normal private files (not encrypted on disk). They are encrypted in Drive like everything else.
- If you forget the vault password, the synced copy cannot be recovered. There is no reset.
- The fingerprint unlocks the app; it is not tied to the encryption key.
- Large notes (over about 200 KB) skip the instant relay and sync through Drive.
- Real-time updates need the app open or the edge bar on; otherwise devices catch up on the next open or background run.
- Phone makers' battery managers (Xiaomi, Honor) can limit the edge bar and the background sync.

## Project notes

`PLAN.md` is the original design plan. The app has since moved from a notification-based idea to the edge bar and card described above.
