<p align="center">
  <img src="docs/img/hero.svg" alt="Scratchy: a note card that floats over any app" width="100%">
</p>

<p align="center">
  <b>Tap the edge of your screen. Write the idea. Close it.</b><br>
  A personal Android notes app, fast first, private second.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-no%20Compose%2C%20no%20XML-7f52ff?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="End-to-end encrypted sync" src="https://img.shields.io/badge/sync-end--to--end%20encrypted-5fe6cf?style=flat-square">
  <img alt="Offline first" src="https://img.shields.io/badge/works-offline-informational?style=flat-square">
</p>

---

You are watching a video, reading an article, in the middle of a chat, and an idea shows up. A thin bar on the edge of your screen opens a note card **right on top of what you are doing**. You type, close it, and carry on. You never left the app.

<br>

<table>
  <tr>
    <td width="50%"><img src="docs/img/categories.svg" alt="Categories"></td>
    <td width="50%"><img src="docs/img/vault.svg" alt="Secret vault"></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/img/sync.svg" alt="Private sync"></td>
    <td width="50%"><img src="docs/img/colors.svg" alt="Your colors"></td>
  </tr>
</table>

<br>

## Everything it does

**Capture without leaving where you are**
- A slim bar sits on the left or right edge of the screen. Tap or swipe it and the card opens with a small "genie" animation, then shrinks back into the bar when you close it.
- Drag the card, resize it, make it see-through or blurry like glass, or pin it so it stays while you keep using the phone.
- Take a screenshot and the bar changes color. Open the card and Scratchy asks where it goes: the current note, a new one, or another note.

**Notes that are nice to read and write**
- A two-column grid of cards, with pinned notes first, search, and big image previews.
- Checklists, pictures inside the note, paste from the clipboard.
- Hold a note to delete it, and undo if you were too fast.
- Everything follows your wallpaper colors (Material You), in light and dark. Prefer one fixed color? Pick it in Settings.

**Categories that mostly sort themselves**
- Put a note in a category by hand, or tap **Sort with AI** and every uncategorized note is filed for you. If a note fits nothing you have, it gets a new category of its own.
- While you write a new note, a small chip quietly suggests a category. Tap it to accept, ignore it and it fades. No popups, and you can switch it off in Settings.
- Filter the whole list with one tap on a category chip.

**Titles from AI, only when you ask**
- Tap the sparkle next to a title and get three options, from short to detailed, based on the text or the first image. Works in any language.

**A secret vault, hidden on purpose**
- Pull the notes list down, slowly and a long way. Nothing shows at first. Keep going and a lock appears with a small buzz. Let go and the vault opens. A normal scroll never triggers it.
- Protected by a password and your fingerprint. Secret notes are hidden everywhere else: lists, search, the card, the screenshot picker. They are encrypted on the phone, and screenshots are blocked on those screens.

**Sync across your devices, privately**
- Connect Google Drive and your notes show up on your other phone or tablet. Everything is encrypted on your device with a key made from your vault password before it leaves, so Drive only ever holds scrambled data.
- Edits appear on the other device in about a second.
- Two devices edit the same note at once? Changes to different lines are merged. If the same line changed on both, nothing is lost: one version stays and the other is saved as a "(conflict)" copy.
- Works offline, and catches up when it can.

## Privacy, plainly

- Your notes live in the app's private storage on your phone.
- Nothing is sent anywhere unless you turn on sync or use an AI feature.
- AI features send only the note you asked about (or the notes being sorted), only when you tap, to OpenRouter with a key you paste in yourself.
- Synced data is end-to-end encrypted. If you forget the vault password, the synced copy cannot be recovered. There is no reset.

## Try it

Scratchy is not on the Play Store. You build it yourself and install it over USB. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the steps, how sync is set up, how it works inside, and what is still to polish.

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

On first run, allow "Display over other apps" and photo access, then switch the edge bar on from the notes screen.

## Made with

Kotlin and plain Android Views (no Compose, no XML layouts). A tiny Cloudflare Worker passes encrypted updates between devices. Android 8.0 and up.
