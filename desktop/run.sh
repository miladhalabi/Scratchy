#!/bin/sh
# Builds the page and the Tauri shell, then runs Scratchy Desktop (pass --toggle to open the card at once).
# Needs webkit2gtk-4.1 and gtk-layer-shell. If WebKitGTK wants libjxl.so.0.12 (half-updated Arch), a private
# copy in .libjxl12 is used.
set -e
cd "$(dirname "$0")"
# Google sign-in credentials (kept out of git).
[ -f .env.local ] && { set -a; . ./.env.local; set +a; }
pnpm --filter @scratchy/desktop build
cargo build -p scratchy-desktop
# gtk-layer-shell must load before libwayland-client.
LD_LIBRARY_PATH="$(pwd)/.libjxl12:$LD_LIBRARY_PATH" LD_PRELOAD=/usr/lib/libgtk-layer-shell.so exec ./target/debug/scratchy-desktop "$@"
