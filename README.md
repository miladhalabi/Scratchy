<h1 align="center">
  <img src="docs/img/icon.png" width="96" alt=""><br>
  Scratchy
</h1>

<p align="center">
  A quick-note app for Android that opens a note card <b>over whatever you are doing</b>.<br>
  Tap the edge of the screen, write the idea, close it. You never left the app.
</p>

<table>
  <tr>
    <td width="33%"><img src="docs/screenshots/edge-bar.jpg"><br><sub><b>The edge bar.</b> The thin teal line on the right edge, on top of any app.</sub></td>
    <td width="33%"><img src="docs/screenshots/card.jpg"><br><sub><b>The card.</b> Opens over the screen with your last note ready, glass blur behind it.</sub></td>
    <td width="33%"><img src="docs/screenshots/screenshot-chooser.jpg"><br><sub><b>Screenshots.</b> Take one and the card asks where it goes.</sub></td>
  </tr>
  <tr>
    <td width="33%"><img src="docs/screenshots/notes.jpg"><br><sub><b>Your notes.</b> A grid of cards with category chips on top.</sub></td>
    <td width="33%"><img src="docs/screenshots/notes-screenshot-waiting.jpg"><br><sub><b>Screenshot waiting.</b> The bar turns green until you file it.</sub></td>
    <td width="33%"><img src="docs/screenshots/sync.jpg"><br><sub><b>Sync.</b> Encrypted on the phone, stored in your own Google Drive.</sub></td>
  </tr>
</table>

## What it does

**Capture**
- A slim bar on the left or right edge. Tap or swipe it and the card opens, with a small genie animation. Close it and it shrinks back.
- Drag and resize the card, change its opacity and blur, or pin it so it stays while you use the phone.
- Checklists, images in notes, paste from the clipboard.
- Take a screenshot and the bar turns green. Open the card and pick where it goes: the current note, a new one, or another note.

**Organize**
- Notes in a two-column grid, pinned first, with search.
- **Categories.** Pick one by hand, or tap *Sort with AI* and every uncategorized note gets filed. If a note fits none of your categories, it gets a new one.
- While you write, a small chip can suggest a category. Tap to accept, or ignore it. You can turn it off in Settings.
- AI titles on request: tap the sparkle by the title for three options. Works in any language.

**Keep private**
- A **vault** for secret notes, behind a password and your fingerprint. Pull the notes list down a long way, and a lock appears; let go to open it. A normal scroll never does.
- Secret notes are hidden from every list and search, encrypted on the phone, and screenshots are blocked on those screens.

**Sync**
- Optional sync through your own Google Drive. Notes are encrypted on the phone with a key made from your vault password, so Drive only holds scrambled data.
- Changes reach your other devices in about a second. If two devices edit the same note, different lines are merged, and a clash is kept as a "(conflict)" copy instead of being lost.

**Looks**
- Colors follow your wallpaper (Material You), light and dark, or use one fixed color from Settings.

## Privacy

- Notes live in the app's private storage. Nothing leaves the phone unless you turn on sync or use an AI feature.
- AI features send only the note you asked about (or the notes being sorted), only when you tap, to OpenRouter, with a key you paste in yourself.
- If you forget the vault password, the synced copy cannot be recovered. There is no reset.

## Install

Not on the Play Store. Build it and install it over USB (JDK 17+, Android SDK, a phone with USB debugging):

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

On first run, allow "Display over other apps" and photo access, then switch the edge bar on from the notes screen. Sync needs a free Google Cloud project, and the instant updates need a free Cloudflare Worker. The steps are in the [developer notes](docs/DEVELOPMENT.md#setting-up-sync).

Kotlin, plain Android Views, Android 8.0 and up. How it works inside is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
