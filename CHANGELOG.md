# Changelog

What changed in each release of Scratchy, newest first. Versions follow [Semantic Versioning](https://semver.org).

## 1.0.0 — 2026-11-12

The first public release, for Android and for Linux (Arch, tested on Hyprland).

### Capture without leaving
- An edge bar on the side of the phone's screen, above every app; tap or swipe it to open the note card over whatever you are doing. On the desktop, Super+Alt+N or the tray icon opens the same card, even over a fullscreen video.
- The card can be dragged, resized, pinned while you keep using the phone, and opened with a genie, a slide or no animation. It can open on your recent note, your last note, one permanent scratch note, or a new one.
- **Normal or compact card**, chosen in Settings → Card, and switched on the open card with the button next to the pin.
- Screenshots file themselves: the bar (or tray icon) turns green, and the card asks which note they go to, or attaches them on its own.
- **Copying from the card:** a long-press selects the whole line, dragging grows it a line at a time, and a small bubble beside the selection offers Copy, Cut, Paste, + Line and All, with the usual selection handles.
- **Blur** behind the card, over the whole screen or only under the card.

### Writing
- A rich editor without symbols: bold, italic, strike, code, links, headings, bullet, numbered and check lists, quotes, real tables, indenting. Notes are saved as plain markdown.
- Paste from any AI app arrives formatted. Format with AI adds structure without changing a single word, checked letter by letter.
- Right-to-left text, with Arabic lists and checkboxes on the right side.

### Organizing
- A grid of notes with pinned notes first, image previews, search, and categories you can file by hand or sort with AI.
- Delete with undo instead of "are you sure".
- Secret notes in a hidden vault, with a password (and fingerprint on the phone), left out of every list, search, the card and AI.

### Sync
- Sync between the phone and the desktop in about a second, end-to-end encrypted (AES-256-GCM, a key from your vault password), kept in your own Google Drive's hidden app folder.
- Line-by-line merging; clashing edits keep the other version as a "(conflict)" note instead of losing it.
- A small relay passes encrypted changes between your devices and stores nothing. Settings → Sync → Advanced lets you use your own.

### Open source
- Released under the GPL-3.0-or-later license, with contributing and security guides, CI on every change, and signed releases built on GitHub.
