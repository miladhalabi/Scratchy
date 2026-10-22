# Scratchy — Android scratchpad that lives in the notification tray

## Goal
Capture a thought in under 2 seconds from anywhere on the phone, without opening an app. The scratchpad is a persistent presence in the system UI (shade, Quick Settings, share sheet, lock screen), and the full app is only for review and organizing.

## Primary use case (decided with the user)
*"I'm watching YouTube, an idea hits, and I don't want to leave the app."*

- **Primary capture path: floating overlay box.** A small draggable handle sits on top of all apps. Tap → tiny text box + keyboard → Enter saves and collapses. One tap, type, Enter. Because it is a window and not an `Activity`, the foreground app is never paused, so YouTube keeps playing.
  - Built with `WindowManager` + `TYPE_APPLICATION_OVERLAY`, hosting a `ComposeView`. The window is non-focusable while collapsed (touches pass through to the app) and becomes focusable only when expanded, so the keyboard can open.
  - Needs the one-time "Display over other apps" permission (`SYSTEM_ALERT_WINDOW`), requested in onboarding with an explainer.
  - Runs from a foreground service (type `specialUse`) whose ongoing notification doubles as the fallback path below. Start on boot if the user enabled it.
  - Handle behavior: snaps to the screen edge, remembers position, semi-transparent when idle, optional auto-hide in fullscreen video, long-press to open the app, drag to a dismiss target to hide.
- **Fallback path: notification inline reply** (WhatsApp-style, a few more taps) from the same foreground-service notification. Works if the overlay permission is denied.
- **Secondary:** Quick Settings tile, share target, widget. These launch activities, so they are not for the in-video case.
- **Notes model (v1):** quick idea list. Every capture is a timestamped item in an inbox; organizing happens later in the app.
- **Deferred:** Bubbles (the system-native alternative to the overlay) and voice dictation.
- **Things to verify on a real device in the Phase 1 spike:**
  - YouTube keeps playing when the overlay takes keyboard focus.
  - The keyboard behaves in fullscreen landscape video, where it may cover the whole screen.
  - The overlay is not killed by the phone maker's battery manager.
- **Trade-offs accepted:** overlay apps need a permission that some find scary, and Play Store review needs a clear in-app disclosure. Fine for personal use or sideloading.

## Principles
- **Zero-launch capture**: the most common action (jot something down) never opens the main app.
- **Use platform primitives, not hacks**: notifications, tiles, widgets, share targets. No `SYSTEM_ALERT_WINDOW` overlays, no accessibility-service tricks, no always-on foreground service.
- **Local-first, private by default**: Room DB on device, no account, no network permission in v1.
- **Survive the system**: reboot, app update, swipe-dismiss and Doze must not lose the tray entry or any text.

## Entry points ("close to the user")

| Surface | What it does | API |
|---|---|---|
| **Persistent notification** (core) | Shows the pinned/latest note. Inline text field ("Jot something…") captures notes right from the shade. Actions: Copy, Clear, Open | `NotificationCompat` + `RemoteInput`, `setOngoing`, `deleteIntent` to re-post |
| **Quick Settings tile** | One tap from the shade opens a tiny capture sheet over whatever app is open | `TileService` + `startActivityAndCollapse(PendingIntent)` (API 34 overload) |
| **Share target** | Share text/links/images from any app into Scratchy | `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, Direct Share shortcut |
| **Text selection menu** | "Save to Scratchy" when selecting text in any app | `ACTION_PROCESS_TEXT` |
| **Home/lock-screen widget** | Latest notes + "+" button; optional quick-add field | Jetpack Glance |
| **Launcher shortcuts** | Long-press icon → New note, Paste clipboard, Open pinned | static + dynamic shortcuts |
| **Voice** | "Hey Google, note … in Scratchy" | App Actions (`actions.xml`, `CREATE_NOTE` BII) — stretch |
| **Clipboard** | "Save clipboard" action (background clipboard reads are blocked since Android 10, so only on user action/foreground) | `ClipboardManager` from tile/shortcut activity |

Optional later: **Bubbles** for a floating note (needs a conversation-style notification, so only if it fits cleanly).

## Notification behavior (the heart of the app)
- Channel `scratch_persistent`, importance LOW (silent, no heads-up), `VISIBILITY_PRIVATE` on lock screen with a public version ("Scratchy"). User setting to show content on lock screen.
- Body: pinned note, or last 3 entries via `InboxStyle`; expandable via `BigTextStyle`.
- Inline reply → save entry → re-post the notification with new state (must re-post or the reply spinner hangs).
- **Android 14+ lets users swipe away ongoing notifications.** Handle with `deleteIntent` → receiver re-posts, plus a "Show in tray" toggle in settings so the user is never fought. Respect that if the user turns the channel off.
- Re-post on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, and on app start.
- Android 13+: request `POST_NOTIFICATIONS` with an explainer in onboarding; degrade gracefully (tile + widget still work) if denied.
- No foreground service: a plain posted notification avoids Play FGS policy and battery cost. Revisit only if the system kills the entry in practice.

## Data model
- `Note(id, text, createdAt, updatedAt, pinned, archived, source[notification|tile|share|selection|widget|app], color?)`
- `Attachment(noteId, uri, mime)` (shared images/links, stored in app-private storage)
- FTS4/FTS5 virtual table for search.
- Single "Scratch" pad concept + individual entries: tray shows the pinned entry; app shows the list.
- Export/import: Markdown/JSON via Storage Access Framework (`CreateDocument`/`OpenDocument`), which is also the backup story for v1.

## Stack
- Kotlin, Jetpack Compose (Material 3, dynamic color), Glance for widgets
- Room, DataStore (settings), Coroutines/Flow
- Hilt (or manual DI; app is small)
- minSdk 26, targetSdk 35+, compileSdk latest
- Tests: JUnit + Turbine for repos/viewmodels, Compose UI tests, instrumented tests for `RemoteInput` flow and tile

## Architecture
```
app/
  data/        Room DB, DAO, NoteRepository
  domain/      Note, use cases (AddNote, PinNote, ClearNote)
  system/      NotificationController, ScratchTileService, BootReceiver,
               NoteActionReceiver (reply/copy/clear/delete), ShareReceiverActivity,
               ProcessTextActivity, ClipboardHelper
  ui/          Compose app (list, editor, search, settings), QuickCaptureActivity (dialog-themed)
  widget/      Glance widgets
```
`NotificationController` is the single place that renders tray state from the DB (observe Flow → post). Every entry point just writes to the repository; the tray, widget, and app update reactively.

## Phases

**Phase 0 — Skeleton (½ day)**
Project setup, Room, repository, Compose list screen, basic add/edit/delete.

**Phase 1 — Tray MVP (core value, the YouTube scenario)**
Spike first: a bare floating overlay (handle → text box → save) running from a foreground service, tested on a real device while a YouTube video plays. Then add the notification fallback.
Persistent notification with inline reply, Copy/Clear/Open actions, permission flow, boot/update re-post, dismiss handling. *Done when: reboot the phone, type into the shade, see the note in the app.*

**Phase 2 — Fast capture surfaces**
Quick Settings tile + `QuickCaptureActivity`, share target, `PROCESS_TEXT`, launcher shortcuts.

**Phase 3 — Widgets & lock screen**
Glance widget (latest notes, add button), lock-screen privacy options.

**Phase 4 — Organize**
Search (FTS), pin/archive, checklist lines (`- [ ]`), undo snackbar, Markdown export/import.

**Phase 5 — Polish & release**
Onboarding, settings (tray content, lock-screen visibility, haptics), accessibility (TalkBack labels, font scaling), themed icon, baseline profile, Play listing, privacy policy (no data collected).

**Later / stretch**
Voice via App Actions, Bubbles, Wear OS tile, end-to-end encrypted sync or Syncthing/SAF folder sync, reminders on a note, OCR on shared images.

## Risks & decisions
- **Persistent notification fatigue**: make it opt-in/out in one tap, low importance, collapsible. Onboarding explains why.
- **OEM battery managers** (Xiaomi, Samsung, etc.) may kill/hide notifications: test on 2–3 OEM skins; add a "Fix notification disappearing" help screen.
- **Tile launching activities** changed in API 34 (needs `PendingIntent` overload): handle both paths.
- **Clipboard privacy**: never read the clipboard in the background; only on explicit user action.
- **Lock-screen leaks**: default to hiding note content on lock screen.
- **Play policy**: no FGS, no overlay permission, minimal permissions (`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`).

## Open questions for you
1. One rolling scratchpad (like a single text blob) or a list of separate notes? (Plan assumes list + one pinned in tray.)
2. Do you want sync across devices, or is local plus export fine for v1?
3. Distribution: personal sideload / F-Droid, or Play Store? (Affects policy work and signing.)
4. Target Android versions and test devices?
5. App name and look: is "Scratchy" final, and any style preference (Material You vs custom)?
