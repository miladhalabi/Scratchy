# Contributing to Scratchy

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Before you start

- **Bugs and ideas:** open an issue first, with the templates. For anything bigger than a small fix, say what you plan to do before you write it, so we can agree on the shape.
- **Security problems:** please don't open a public issue; see [SECURITY.md](SECURITY.md).
- By contributing you agree that your work is released under the project's [GPL-3.0-or-later](LICENSE) license.

## The pieces

| Folder | What it is | Needs |
| --- | --- | --- |
| [`app/`](app) | The Android app: Kotlin, plain Android Views (no Compose, no XML layouts) | JDK 17, Android SDK (platform 35) |
| [`desktop/`](desktop) | The desktop app: Tauri 2, Svelte 5 and TypeScript, a little Rust | Node.js 24, pnpm 11, Rust; on Linux webkit2gtk-4.1 and gtk-layer-shell |
| [`desktop/packages/core`](desktop/packages/core) | Sync, merge, encryption and markdown, shared format with the phone | Node.js |
| [`worker/`](worker) | The Cloudflare Worker that relays encrypted changes | Node.js, a Cloudflare account to deploy |

[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) has how sync works, the code map, and setting up Google Drive and the relay.

## Build and test

Android:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Desktop:

```bash
cd desktop
pnpm install
pnpm -r test
pnpm --filter @scratchy/desktop check
./run.sh
```

Every pull request runs the same checks in CI.

## How the code is written

- **Match the code around you:** its naming, its comments and how much it comments. Comments say why, in plain words.
- **Keep the phone and the desktop in step.** They share one sync format (`SyncData.kt` and `packages/core/src/format.ts`, merge, encryption). A change to the format goes into both, with tests on both sides; `packages/core/test/fixtures/vectors.json` checks that they agree byte for byte.
- **Never rewrite a note the user only opened.** An untouched note keeps its exact text.
- **Privacy first.** Nothing leaves the device unless the user turned on sync or asked for an AI feature, and secret notes never go to AI.
- **No new dependency without a good reason;** say why in the pull request.
- **Write in plain English** in the UI, without jargon, and keep right-to-left text working.

## Pull requests

- One topic per pull request, with tests for logic changes (merge, markdown, sync, rich text).
- Say what you changed and how you checked it; for UI changes, a screenshot or a short recording.
- Commit messages in the style of the history: a short subject saying what changed for the user, then why.
