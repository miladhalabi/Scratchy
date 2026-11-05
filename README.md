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

## 💡 Why it exists

An idea shows up while you are watching a video, reading, or mid-chat. Opening a notes app means leaving everything, and by the time you are back, the thought is gone. Scratchy puts the note **on top of what you are doing**, then gets out of your way.

## ⚡ Capture, without leaving where you are

- 📍 **An edge bar that is always there.** A thin line on the left or right edge of the screen, on top of every app, even a fullscreen video. Choose the side and how far from the edge it sits. It tucks away when you are not using it.
- 🧞 **Tap or swipe it and the card opens** with a genie animation, as if the note is pulled out of the bar. Close it and it is sucked back in. Prefer a plain slide, or nothing? One setting.
- 🪟 **The card is a real window.** Drag it anywhere, resize it from the corner, change its opacity and blur, pick a solid or liquid-glass look.
- 📌 **Pin it** and the card stays on screen while you carry on using the phone: copy from a page, glance at the card, paste.
- 🧠 **It remembers.** The card opens on your last note ready to continue, on one permanent scratch note, or on a fresh one. You choose.
- 📸 **Screenshot? One tap to file it.** The bar turns green the moment you take one. Open the card and choose: add to the current note, start a new note, or pick another note. Or let it attach on its own.
- 📋 **A real editor, over any app.** Images inside the note, paste from the clipboard (including images from the keyboard), and its own copy, cut and paste bar, because an overlay window cannot show Android's text menu.

## ✍️ A rich editor, no symbols in sight

Nobody has to know markdown. You format with buttons, and the note is still saved as plain markdown underneath, so it pastes cleanly into ChatGPT, Notion or Obsidian.

- 🔠 **The Aa panel.** One button in the note's top bar opens a roomy panel of large, labelled tiles: **Bold, Italic, Strike, Code, Link**, three **heading** sizes, **Bullets, Numbers, Checklist, Quote**, **Table**, and **Indent / Outdent**. The tile lights up for whatever your cursor is in. It never takes screen space when you are not using it, and it never takes the keyboard or your selection.
- 📊 **Real tables.** A table is drawn as an actual grid, with a tinted header, rounded borders and cells that wrap. Tap it and a table editor opens: type in cells, add or delete rows and columns, or delete the table. Wide tables scroll sideways inside the editor.
- ☑️ **Real lists.** Checklists show as checkboxes you tap. Bullets and numbered lists continue on Enter, end on an empty line, nest with Indent, and Backspace at the start of an item removes the list instead of joining lines.
- ⌨️ **Notion-style shortcuts.** Type `- `, `1. `, `# `, `> ` or `[] ` at the start of a line and it turns into that format.
- 📋 **Paste from any AI app.** Copy an answer with the app's Copy button and paste it: headings, bold, lists, links and tables arrive already formatted, with no markdown symbols left over.
- ✨ **Format with AI.** A sparkle button in the note's top bar turns plain text into a structured note: bullets for lists, headings for titles, bold for key terms, tables where the text is tabular. **Your words stay exactly as they are.** The app compares every letter of the AI's answer with your original and throws the answer away if anything changed, then tries again. A chip under the title shows the progress, with one tap to **Undo**. It works for Arabic and mixed Arabic and English notes too, and only runs in the app, never in the floating card.
- 🌍 **Right-to-left done properly.** Arabic lines get their bullets, numbers and checkboxes on the right, and tapping a checkbox works on the right edge.
- 🪟 **The floating card stays simple.** No formatting buttons there, just fast typing. Formatting and tables you made in the app show up in the card, and the typing shortcuts work.
- 🔁 **Old notes just work.** Your earlier checklists convert themselves. A note you only open and close is never rewritten.

## 🗂️ Organize

- 🧱 **A card-style notes grid** with pinned notes first, big image previews cropped from the middle, and instant search across titles, text and categories.
- 🏷️ **Categories you do not have to think about.** File a note by hand, or tap **✦ Sort with AI** and every uncategorized note is filed in one go. If a note fits none of your categories, it gets a brand new one. Filter the whole list with one tap, hold a chip to rename or remove it.
- 🤫 **Suggestions while you write.** A small chip offers a category after you pause typing. Accept with a tap, ignore it and it fades. It never pops up, never steals focus, and can be switched off.
- ✨ **AI titles on demand.** Tap the sparkle for three options, from three words to a full descriptive line. It reads the text, and the first image too, and answers in the language you wrote in.
- 🗑️ **Hold to delete, with undo.** No "are you sure" for every note, just a bar that lets you take it back.

## 🔒 Keep it private

- 🕳️ **A vault with no door in sight.** Nothing on screen hints it exists. Pull the notes list down a long way, a lock appears with a haptic click, let go and it opens. Pull back and it disappears. A normal scroll can never open it.
- 👆 **Password and fingerprint.** Secret notes are removed from every list, search, the card and the screenshot picker. They are encrypted on the phone with a key that never leaves Android's Keystore, and screenshots and the recent-apps preview are blocked on those screens.
- 🙈 **Your data stays yours.** No account, no ads, no tracking. Nothing leaves the phone unless you turn on sync or ask for an AI feature, and AI only ever sees the note you asked about.

## 🔄 Sync, the way it should work

- ⚡ **Instant across devices.** Edit on your phone and the note changes on your tablet in about **a second**, even while its card is open. A small Cloudflare Worker relays the update, and it stores nothing: it only passes sealed messages along.
- 🔐 **Encrypted before it leaves.** Every note is sealed on your device with **AES-256-GCM**, with a key derived from your vault password (PBKDF2, 120,000 rounds). Google Drive holds only scrambled files in your private app folder, and the relay only ever sees ciphertext. Secret notes sync too.
- 🛡️ **No lost edits, ever.** Each note is its own file. Two devices change different lines of the same note? They are merged line by line. Same line changed on both? The newer edit stays and the other is saved as a "(conflict)" note. Nothing is overwritten silently, and a phone with a wrong clock cannot win.
- ✈️ **Offline is normal.** Write on a plane, and the devices catch up when you are back. A background sync runs every 15 minutes, there is a status line on the notes screen, and deleted notes stay deleted.
- 🔑 **Change your vault password** and everything is re-encrypted for you.

## 🎨 It looks like your phone

- 🖼️ **Material You.** The whole app takes its colors from your wallpaper, light and dark. Change the wallpaper and the app follows. Prefer one fixed color? Choose it in Settings.
- 🌊 **Springs, not stock animations.** Every movement is interruptible and carries your finger's velocity, from the card to the delete bar.
- 💬 **Its own dialogs.** Rounded cards in your colors, not the gray system boxes.

## ⚙️ Settings

A short menu with one small page per topic, and an *Advanced* fold for the rare stuff.

| Page | What you can change |
| --- | --- |
| 📍 **Edge bar** | Left or right side. Distance from the edge (0 to 24 dp), for screen protectors that cover it. Or long-press the bar and drag it. |
| ✏️ **Card** | What the card opens on: **Recent** (continue the last note if you touched it in the last 30 minutes), **Last** (always continue it), **Scratch** (one permanent pinned note) or **New** (always blank). |
| 🎨 **Appearance** | **Color:** follow the wallpaper, or pick a fixed one (blue, orange, teal, green, purple). **Style:** Solid or Liquid glass, with a live preview. **Opacity** 30 to 100%. **Blur** behind the card 0 to 100%. **Opening and closing:** Genie, Slide or None, and **genie speed** 50 to 200%. |
| 📸 **Screenshots** | **Ask** (the bar turns green and the card asks where it goes), **Auto-attach**, or **Off**. *Advanced:* forget a waiting screenshot after 15 s, 30 s, 1, 2 or 5 min. |
| 🔄 **Sync** | Connect Google Drive, **Sync now**, disconnect, see the status, and enter a new vault password after changing it on another device. |
| 🔒 **Secret notes** | Change the vault password (everything in Drive is re-encrypted). |
| ✨ **AI** | Turn **category suggestions** on or off. The same text model is used for titles, categories and **Format with AI**. Paste your **OpenRouter key** and test the connection. *Advanced:* the text model and the image model. |

## 🧰 Built to last

Plain Kotlin and Android Views, no Compose and no XML layouts. The merge engine and the markdown logic are pure Kotlin with 39 unit tests, and the sync format is versioned. About 8,000 lines of Kotlin.

## 🔏 Privacy

- Notes live in the app's private storage.
- AI features send only the note you asked about (or the notes being sorted), only when you tap, to OpenRouter, with a key you paste in yourself.
- If you forget the vault password, the synced copy cannot be recovered. There is no reset.

## 🖥️ On your computer too

Scratchy has a desktop app (Linux first; Windows and Mac are planned). It is the same idea: a shortcut opens a note card over whatever you are doing, even a fullscreen video, and it syncs with the phone in about a second.

- ⌨️ **Super+Alt+N from anywhere** opens the card, or the tray icon does. Esc, a click beside it, or the shortcut again closes it. Pin it to keep it on screen while you use other apps.
- 🧞 **The same card.** Genie, slide (with or without a fade) or no animation, at the speed you like. Drag it anywhere, resize it from any edge, zoom its text with Ctrl+= and Ctrl+-. Make it see-through, with the blur only under the card on Hyprland.
- 🗒️ **A notes window like the phone's.** The grid, search, category chips, the full editor with the Aa panel and tables, images (paste, drop or the Paste button), and a viewer that saves an image to Downloads.
- 📸 **Screenshots land in notes.** Take one with your desktop's screenshot tool (or copy any image) and the tray icon turns green; open the card and choose where it goes.
- 🔄 **Sync and the vault work exactly as on the phone:** the same Google Drive folder, the same encryption, the same password. Secret notes open with Ctrl+Shift+L in the notes window.
- ✨ **The AI features** use your own OpenRouter key, kept in the system keyring.
- 🎨 **Your desktop's accent color**, light and dark following the system, and it starts at login into the tray.

Built with Tauri, Svelte and TypeScript, in [`desktop/`](desktop).

## 📲 Install

Not on the Play Store. Build it and install it over USB (JDK 17+, Android SDK, a phone with USB debugging):

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

On first run, allow "Display over other apps" and photo access, then switch the edge bar on from the notes screen. Sync needs a free Google Cloud project, and the instant updates need a free Cloudflare Worker. The steps are in the [developer notes](docs/DEVELOPMENT.md#setting-up-sync).

### 🖥️ Desktop (Arch Linux)

Build the package from this checkout and install it (Rust, Node.js and pnpm needed):

```bash
cd desktop
pnpm install
cd packaging/arch
makepkg -f
sudo pacman -U scratchy-*.pkg.tar.zst
```

For sync, put the Google desktop client in `desktop/.env.local` before building (`GOOGLE_CLIENT_ID=…` and `GOOGLE_CLIENT_SECRET=…`, the same Google Cloud project as the phone). Then start **Scratchy** from your launcher and add the shortcut to Hyprland (Settings in the app shows the line, with a Copy button):

```
bind = SUPER ALT, N, exec, scratchy-toggle
```

The card is a layer-shell surface, so it shows over fullscreen apps. It is tested on Hyprland (Omarchy); Sway and KDE should work as they have layer-shell too, GNOME isn't supported yet.

Kotlin, plain Android Views, Android 8.0 and up. How it works inside is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
