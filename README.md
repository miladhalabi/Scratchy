# Scratchy

**A note card that floats over any app.**

You are watching a video, reading an article, in the middle of a chat, and an idea shows up. Tap the thin bar on the edge of your screen, a note card opens right there on top of what you are doing, you type the idea, close it, and carry on. You never left the app.

Scratchy is a personal Android notes app, built for speed first and for keeping your notes private second.

<!-- Screenshots go here: the edge bar, the floating card, the notes grid, the vault. -->

## What it feels like

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
