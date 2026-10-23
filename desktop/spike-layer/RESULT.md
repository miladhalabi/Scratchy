# Spike A result: layer-shell + Tauri/WebKitGTK on Hyprland 0.54

Works, with these lessons:

- Create the Tauri window hidden, then `init_layer_shell()` on `win.gtk_window()` before showing. The window is not realized at that point, so it is accepted.
- **Size the window with `set_size_request(w, h)`, not `set_default_size`.** With the default size, the layer surface is configured but GTK never draws it (blank, invisible).
- `LD_PRELOAD=/usr/lib/libgtk-layer-shell.so` is used so the library is loaded before libwayland-client.
- `WEBKIT_DISABLE_DMABUF_RENDERER=1` is used while testing; not yet shown to be required.
- Hyprland lists both windows on layer level 3 (overlay). Transparent rounded card and the bar pill render correctly; the page ran at ~61 fps in WebKitGTK.
- `gtk-layer-shell` crate needs the `v0_6` feature for `set_keyboard_mode`.
- `libjxl.so.0.12` was missing on the test machine (partial system upgrade); a private copy is loaded via LD_LIBRARY_PATH.

Not yet verified: typing in the card (keyboard on demand), dragging by margins, and showing above a fullscreen video.
