# Scratchy: developer notes

The friendly overview is in the [README](../README.md). This page has the build steps, how sync works, the code map and the to-do list.

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
- Categories: a name on each note, chips to filter, pick by hand or let the AI file notes (`Categories.kt`). A quiet chip offers a category while you write (setting under AI titles). Synced and merged like any other field; never set on secret notes.
- Own dialogs (`Dialogs.kt`, class `Sheet`) instead of the system ones.
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
- **Relay**: the Worker only forwards opaque encrypted messages to connected devices of the same account. The account "room" is a hash of the sync key. It stores nothing.
- Devices that are offline catch up from Drive when they open the app, reconnect, or on the 15-minute background run.

## Build and install

Requirements: JDK 17+, Android SDK (platform 35), a device with USB debugging.

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # merge and markdown tests
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is minified (R8). Without a signing key configured it is a personal build, signed with the local debug keystore (`~/.config/.android/debug.keystore`), so it upgrades over debug installs and keeps your data. After an update the edge bar comes back by itself if it was on (`BootReceiver`).

### Signing published releases

Published APKs are signed with your own private key, which never goes in the repository (`*.jks` and `*.keystore` are ignored). Make one once:

```bash
keytool -genkeypair -v -keystore ~/.config/scratchy-release.jks -alias scratchy -keyalg RSA -keysize 4096 -validity 10000
```

and point the build at it in `~/.gradle/gradle.properties` (or the `SCRATCHY_*` environment variables, for CI):

```properties
scratchy.keystore=/home/you/.config/scratchy-release.jks
scratchy.storePassword=…
scratchy.keyAlias=scratchy
scratchy.keyPassword=…
```

Keep a backup of the keystore and its passwords somewhere safe: every update must be signed with the same key, or it can't install over the app already there. Add the key's SHA-1 (`keytool -list -v -keystore ~/.config/scratchy-release.jks -alias scratchy | grep SHA1`) to an Android OAuth client in Google Cloud, or Drive sign-in won't work in those builds. A phone with a debug-signed Scratchy has to uninstall it before the first release-signed install (sync brings the notes back).

An adb install starts uncompiled (`status=verify`), so the card is slower until the phone's overnight optimization runs. The app ships a startup profile (`app/src/main/baseline-prof.txt`, installed by `androidx.profileinstaller`); before measuring performance, compile with it right away, then restart the app:

```bash
adb shell cmd package compile -m speed-profile -f com.scratchy.app
```

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
5. While the consent screen is in "Testing", Google expires the permission after about 7 days. **Publish the app** to stop that (done for this project: it is *In production*). Publishing only changes the sign-in screen's status; it does not put the app in any store. Google first wants these on the branding page, which the relay Worker serves (deploy it first, see below):

   | Field | Value |
   | --- | --- |
   | Application home page | `https://<your worker>.workers.dev/` |
   | Privacy policy link | `https://<your worker>.workers.dev/privacy` |
   | Terms of service link | `https://<your worker>.workers.dev/terms` |
   | Authorized domains | `<your account>.workers.dev` (`workers.dev` is a public suffix, so Google accepts it as yours) |
   | App logo | **leave empty**: a logo means brand verification and a review of several days |

   Then **Audience → Publish app**. The only scope used is `drive.appdata`, which is non-sensitive, so no verification is needed. Sign in again once on every device afterwards: sign-ins made while testing keep their 7-day expiry.

Use the **same vault password on every device**.

### 2. Live relay (Cloudflare Worker)

The relay is optional but gives the near-instant updates. It runs on Cloudflare's free plan.

```bash
cd worker
npx wrangler login
npx wrangler deploy
```

The apps use the project's relay unless you set another: **Settings → Sync → Advanced → Relay server**, on every device, with the Worker's address (`https://…workers.dev`, no path). The app checks that it answers like a relay before using it. To change the default for everyone in your own builds, edit `DEFAULT_RELAY` in `app/src/main/java/com/scratchy/app/Live.kt` and `desktop/apps/desktop/src/lib/settings.ts`. Without a reachable relay, sync still works through Drive (a few seconds slower).

The relay stores nothing: it passes sealed note changes (`POST /v1/<room>/relay`) and a "go look at Drive" ring (`POST /v1/<room>/tick`) to the room's open WebSockets (`/v1/<room>/ws`, at most 32 per room).

The Worker also serves the home page, privacy policy and terms that Google's consent screen links to (`worker/src/pages.js`, at `/`, `/privacy` and `/terms`). Change the contact address in that file to your own before deploying.

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
| `Markdown.kt` | Pure markdown helpers: list and checkbox logic, `legacy()` for old ☐/☑ notes, `words()`/`letters()` for the AI word check, `render()` for the read-only grid and previews |
| `RichText.kt` | The rich editor's model: paragraph and inline spans, `fromMarkdown()` and `toMarkdown()`, list continuation, shortcuts, tables as one placeholder character |
| `TableEditor.kt`, `FormatBar.kt` | The table editing sheet, and the drawn icons used by the Aa panel and the AI button |
| `Categories.kt`, `Dialogs.kt` | Categories (names, AI sorting, pickers) and the app's own dialog card |
| `Ai.kt`, `Attachments.kt`, `Screenshots.kt`, `Checklist.kt`, `HoldToDelete.kt`, `Appearance.kt`, `CardNote.kt`, `Ui.kt` | Supporting pieces |

`worker/` holds the Cloudflare Worker and its Durable Object (`src/index.js`), and the public pages for Google's consent screen (`src/pages.js`).

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

## To do: UI polish

The color system (follows the wallpaper, with a fixed-color fallback) and the new Settings menu are done. What is left, roughly in the order I would do it:

**First**
- [ ] **Screenshot-waiting bar color.** The bar turns green when a screenshot is waiting, but on a teal wallpaper the normal bar is also teal-green. Give the waiting state a clearly different color or cue.
- [x] **Dialogs.** Now the app's own rounded, palette-colored `Sheet`. Toasts are still the system ones.
- [ ] **Notes list header.** Three stacked status lines above the search bar (edge bar, "Sync: Synced 16:53", permission prompts). Merge into one quiet line, or show sync state as a small icon next to the gear.
- [ ] **Edge bar icon** in the Settings menu looks like a slash. Replace it with a proper bar/edge icon.
- [ ] **Light mode.** Check every screen with the new wallpaper colors.
- [ ] **Tablet.** The notes grid uses two columns everywhere; use three or four on wide screens, and narrow the Settings pages.

**Then**
- [ ] **Floating card** with the new colors: header icons, bottom Close strip, continue row, screenshot chooser, AI title chips, glass style on a teal palette.
- [ ] **Touch targets.** Card header icons are 36dp wide; Android recommends 48dp.
- [ ] **Note editor.** The top row of six grey icons is crowded.
- [ ] **Notes grid.** Make the pinned marker easier to see; "No text" shows on notes that only have a title; "(conflict)" notes look like ordinary notes.
- [ ] **Vault.** Lock screen and the lock that appears on the pull-down.
- [ ] **Settings pages not reviewed yet:** Edge bar, Card, Screenshots, Sync, Secret notes, AI titles.
- [ ] **Floating "+" button.** Check contrast on teal.
- [ ] **Empty states, undo bar and toasts:** wording and spacing.

**Other open items**
- [x] Publish the Google OAuth app so the Drive permission stops expiring every 7 days. In production since 12 Nov 2026, without a logo.
- [ ] Try the vault password change on two real devices.
- [ ] Look at the secret-notes images: they are hidden in the app but not encrypted on disk.

## Project notes

`PLAN.md` is the original design plan. The app has since moved from a notification-based idea to the edge bar and card described above.

## Screenshots without showing your notes

`DemoData.kt` seeds made-up notes, but only when the package name ends in `.demo`. Build a separate copy that installs next to the real app and never touches its data:

```bash
./gradlew assembleRelease -PappId=com.scratchy.app.demo
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set com.scratchy.app.demo SYSTEM_ALERT_WINDOW allow
```

Screenshots with `adb exec-out screencap -p`, crop the status bar, then `adb uninstall com.scratchy.app.demo`. Rebuild without `-PappId` for the real app. Secret screens block screenshots by design, so the vault itself can't be captured.
