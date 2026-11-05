//! The floating card's window.
//!
//! It is one transparent surface over the whole screen the mouse is on, created hidden at startup with the page
//! already loaded. The card is drawn inside it, so dragging, resizing and the genie never move or resize the
//! window itself (moving a layer surface by its margins was what made the first version stutter).
//!
//! Unpinned, the surface takes every click (a click beside the card closes it) and the keyboard. Pinned, only the
//! card's own rectangle takes input, so the apps underneath stay usable.
//!
//! On Linux/Wayland it is a layer-shell surface on the overlay layer, the only kind drawn above fullscreen apps.

use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

/// Whether the card's window is mapped right now (the page decides when to hide it, after its closing animation).
static SHOWN: AtomicBool = AtomicBool::new(false);
/// `scratchy --toggle` started the app: open the card as soon as the page is ready.
pub static OPEN_ON_BOOT: AtomicBool = AtomicBool::new(false);
/// The surface is shown once right after startup, invisibly, so the first real open doesn't pay for setting it up.
static WARMING: AtomicBool = AtomicBool::new(false);

fn builder<'a>(app: &'a AppHandle) -> WebviewWindowBuilder<'a, tauri::Wry, AppHandle> {
    WebviewWindowBuilder::new(app, "card", WebviewUrl::App("index.html?w=card".into()))
        .disable_drag_drop_handler()
        .title("Scratchy card")
        .transparent(true).decorations(false).shadow(false).resizable(false).visible(false).skip_taskbar(true)
}

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use gtk::prelude::*;
    use gtk_layer_shell::{Edge, KeyboardMode, Layer, LayerShell};

    pub fn create(app: &AppHandle) -> WebviewWindow {
        let win = builder(app).build().expect("card window");
        let g = win.gtk_window().expect("gtk window");
        // Must happen before the window is realised (see the spike notes in git history, 32984c1).
        g.init_layer_shell();
        g.set_layer(Layer::Overlay);
        g.set_namespace("scratchy");
        g.set_exclusive_zone(-1);
        for e in [Edge::Top, Edge::Bottom, Edge::Left, Edge::Right] { g.set_anchor(e, true); }
        g.set_keyboard_mode(KeyboardMode::Exclusive);
        g.set_app_paintable(true);
        win
    }

    /// No monitor is set, so the compositor maps it on the focused one: the screen the mouse is on.
    pub fn show(win: &WebviewWindow) { if let Ok(g) = win.gtk_window() { g.show_all(); } }
    pub fn hide(win: &WebviewWindow) { let _ = win.hide(); }

    /// Only this rectangle takes clicks (None = the whole screen).
    pub fn input(win: &WebviewWindow, rect: Option<(i32, i32, i32, i32)>) {
        if let Ok(g) = win.gtk_window() {
            match rect {
                Some((x, y, w, h)) => {
                    let r = gtk::cairo::Region::create_rectangle(&gtk::cairo::RectangleInt::new(x, y, w.max(1), h.max(1)));
                    g.input_shape_combine_region(Some(&r));
                }
                None => g.input_shape_combine_region(None),
            }
        }
    }

    /// Shown with nothing to click and no keyboard: the apps underneath don't notice it.
    pub fn show_quietly(win: &WebviewWindow) {
        if let Ok(g) = win.gtk_window() {
            g.set_keyboard_mode(KeyboardMode::None);
            let empty = gtk::cairo::Region::create();
            g.input_shape_combine_region(Some(&empty));
            g.show_all();
        }
    }

    /// Exclusive: typing goes to the card the moment it opens. On demand: only after a click (pinned).
    pub fn keyboard(win: &WebviewWindow, exclusive: bool) {
        if let Ok(g) = win.gtk_window() {
            g.set_keyboard_mode(if exclusive { KeyboardMode::Exclusive } else { KeyboardMode::OnDemand });
        }
    }
}

#[cfg(not(target_os = "linux"))]
mod imp {
    // Windows and Mac come later (topmost window / NSPanel); this keeps the build working.
    use super::*;

    pub fn create(app: &AppHandle) -> WebviewWindow {
        builder(app).always_on_top(true).maximized(true).build().expect("card window")
    }
    pub fn show(win: &WebviewWindow) { let _ = win.show(); let _ = win.set_focus(); }
    pub fn show_quietly(_win: &WebviewWindow) {}
    pub fn hide(win: &WebviewWindow) { let _ = win.hide(); }
    pub fn input(_win: &WebviewWindow, _rect: Option<(i32, i32, i32, i32)>) {}
    pub fn keyboard(_win: &WebviewWindow, _exclusive: bool) {}
}

pub fn setup(app: &AppHandle) { imp::create(app); }

fn card(app: &AppHandle) -> Option<WebviewWindow> { app.get_webview_window("card") }

fn on_main(app: &AppHandle, f: impl FnOnce(&AppHandle) + Send + 'static) {
    let a = app.clone();
    let _ = app.run_on_main_thread(move || f(&a));
}

/// The shortcut or the tray: maps the window if needed, then lets the page open or close the card.
pub fn toggle(app: &AppHandle) {
    on_main(app, |app| {
        if let Some(c) = card(app) {
            if WARMING.swap(false, Ordering::SeqCst) {
                // Opened during the warm-up: it is already mapped, so give it back its input and keyboard.
                SHOWN.store(true, Ordering::SeqCst);
                imp::input(&c, None);
                imp::keyboard(&c, true);
            }
            if !SHOWN.swap(true, Ordering::SeqCst) { imp::show(&c); }
            let _ = c.emit("card-toggle", ());
        }
    });
}

/// Right after the page is ready: map the surface for a moment with nothing on it, then unmap it.
pub fn warm_up(app: &AppHandle) {
    if !cfg!(target_os = "linux") { return }
    on_main(app, |app| {
        let Some(c) = card(app) else { return };
        if SHOWN.load(Ordering::SeqCst) { return }
        WARMING.store(true, Ordering::SeqCst);
        imp::show_quietly(&c);
    });
    let a = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(600));
        on_main(&a, |app| {
            if !WARMING.swap(false, Ordering::SeqCst) { return }   // the card was opened meanwhile
            if let Some(c) = card(app) {
                imp::hide(&c);
                imp::input(&c, None);
                imp::keyboard(&c, true);
            }
        });
    });
}

/// Called by the page once its closing animation is done.
pub fn hide(app: &AppHandle) {
    on_main(app, |app| {
        if let Some(c) = card(app) { imp::hide(&c); }
        SHOWN.store(false, Ordering::SeqCst);
    });
}

pub fn input(app: &AppHandle, rect: Option<(i32, i32, i32, i32)>) {
    on_main(app, move |app| { if let Some(c) = card(app) { imp::input(&c, rect); } });
}

pub fn keyboard(app: &AppHandle, exclusive: bool) {
    on_main(app, move |app| { if let Some(c) = card(app) { imp::keyboard(&c, exclusive); } });
}
