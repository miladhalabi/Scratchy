#!/bin/sh
# Builds the web part and the Tauri shell, then runs Scratchy Desktop.
# Needs webkit2gtk-4.1. If WebKitGTK complains about libjxl.so.0.12 (half-updated Arch), a private
# copy in .libjxl12 is used.
set -e
cd "$(dirname "$0")"
# Google sign-in credentials (kept out of git).
[ -f .env.local ] && { set -a; . ./.env.local; set +a; }
pnpm --filter @scratchy/desktop build
cargo build -p scratchy-desktop
LD_LIBRARY_PATH="$(pwd)/.libjxl12:$LD_LIBRARY_PATH" LD_PRELOAD=/usr/lib/libgtk-layer-shell.so WEBKIT_DISABLE_DMABUF_RENDERER=1 exec ./target/debug/scratchy-desktop "$@"
