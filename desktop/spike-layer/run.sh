#!/bin/sh
# Runs the layer-shell spike on Hyprland. Needs: gtk-layer-shell, webkit2gtk-4.1.
# If WebKitGTK complains about libjxl.so.0.12 (half-updated Arch), a private copy in .libjxl12 is used.
cd "$(dirname "$0")/src-tauri" || exit 1
cargo build || exit 1
LD_LIBRARY_PATH="$(cd .. && pwd)/.libjxl12:$LD_LIBRARY_PATH" \
LD_PRELOAD=/usr/lib/libgtk-layer-shell.so \
WEBKIT_DISABLE_DMABUF_RENDERER=1 \
exec ./target/debug/spike-layer
