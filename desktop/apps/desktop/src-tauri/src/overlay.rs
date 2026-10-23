//! The edge bar and the floating card.
//!
//! On Linux/Wayland (Hyprland, sway, ...) they are layer-shell surfaces on the overlay layer, which is the only kind
//! of window that is drawn above fullscreen apps. Elsewhere they are frameless, transparent, always-on-top windows.
//! Everything is placed with logical pixels measured from the top-left corner of the screen.

use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase", default)]
pub struct Prefs {
    /// The bar sits on the right edge instead of the left.
    pub right: bool,
    /// Distance of the bar from the top of the screen.
    pub bar_y: i32,
    /// Gap between the bar and the screen edge.
    pub gap: i32,
    pub card_x: Option<i32>,
    pub card_y: Option<i32>,
    pub card_w: i32,
    pub card_h: i32,
    /// Keep the card on screen when you click elsewhere.
    pub pinned: bool,
}

impl Default for Prefs {
    fn default() -> Self {
        Prefs { right: false, bar_y: 420, gap: 4, card_x: None, card_y: None, card_w: 400, card_h: 460, pinned: false }
    }
}

pub struct State(pub Mutex<Prefs>);

pub const BAR_W: i32 = 22;
pub const BAR_H: i32 = 110;

fn screen(app: &AppHandle) -> (i32, i32) {
    app.primary_monitor().ok().flatten()
        .map(|m| { let s = m.size().to_logical::<f64>(m.scale_factor()); (s.width as i32, s.height as i32) })
        .unwrap_or((1920, 1080))
}

/// Where the card goes when it has no remembered position: next to the bar.
pub fn default_card_pos(app: &AppHandle, p: &Prefs) -> (i32, i32) {
    let (sw, sh) = screen(app);
    let x = if p.right { sw - p.gap - BAR_W - 12 - p.card_w } else { p.gap + BAR_W + 12 };
    let y = (p.bar_y + BAR_H / 2 - p.card_h / 2).clamp(24, (sh - p.card_h - 24).max(24));
    (x, y)
}

fn builder<'a>(app: &'a AppHandle, label: &str, url: &str, w: i32, h: i32) -> WebviewWindowBuilder<'a, tauri::Wry, AppHandle> {
    WebviewWindowBuilder::new(app, label, WebviewUrl::App(url.into()))
        .transparent(true).decorations(false).shadow(false).resizable(false).visible(false)
        .skip_taskbar(true).inner_size(w as f64, h as f64)
}

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use gtk::prelude::*;
    use gtk_layer_shell::{Edge, KeyboardMode, Layer, LayerShell};

    pub fn create(app: &AppHandle, label: &str, url: &str, ns: &str, w: i32, h: i32, keyboard: bool) -> WebviewWindow {
        let win = builder(app, label, url, w, h).build().expect("overlay window");
        let g = win.gtk_window().expect("gtk window");
        g.init_layer_shell();
        g.set_layer(Layer::Overlay);
        g.set_namespace(ns);
        g.set_exclusive_zone(-1);
        g.set_keyboard_mode(if keyboard { KeyboardMode::OnDemand } else { KeyboardMode::None });
        // Layer surfaces are sized with a size request; a default size leaves them blank (see spike-layer/RESULT.md).
        g.set_size_request(w, h);
        g.set_app_paintable(true);
        win
    }

    /// Anchors the window to the top-left (or top-right) and sets its margins.
    pub fn place(win: &WebviewWindow, right: bool, x: i32, y: i32) {
        if let Ok(g) = win.gtk_window() {
            g.set_anchor(Edge::Top, true);
            g.set_anchor(Edge::Left, !right);
            g.set_anchor(Edge::Right, right);
            g.set_layer_shell_margin(Edge::Top, y);
            g.set_layer_shell_margin(Edge::Left, if right { 0 } else { x });
            g.set_layer_shell_margin(Edge::Right, if right { x } else { 0 });
        }
    }

    pub fn resize(win: &WebviewWindow, w: i32, h: i32) {
        if let Ok(g) = win.gtk_window() { g.set_size_request(w, h); g.queue_resize(); }
    }

    pub fn show(win: &WebviewWindow) {
        if let Ok(g) = win.gtk_window() { g.show_all(); }
    }
}

#[cfg(not(target_os = "linux"))]
mod imp {
    use super::*;

    pub fn create(app: &AppHandle, label: &str, url: &str, _ns: &str, w: i32, h: i32, _keyboard: bool) -> WebviewWindow {
        builder(app, label, url, w, h).always_on_top(true).focused(false).build().expect("overlay window")
    }

    pub fn place(win: &WebviewWindow, right: bool, x: i32, y: i32) {
        let size = win.outer_size().ok();
        let scale = win.scale_factor().unwrap_or(1.0);
        let sx = if right {
            let m = win.current_monitor().ok().flatten();
            let sw = m.map(|m| (m.size().width as f64 / scale) as i32).unwrap_or(1920);
            let w = size.map(|s| (s.width as f64 / scale) as i32).unwrap_or(0);
            sw - x - w
        } else { x };
        let _ = win.set_position(tauri::LogicalPosition::new(sx as f64, y as f64));
    }

    pub fn resize(win: &WebviewWindow, w: i32, h: i32) {
        let _ = win.set_size(tauri::LogicalSize::new(w as f64, h as f64));
    }

    pub fn show(win: &WebviewWindow) { let _ = win.show(); }
}

/// Creates the bar (visible) and the card (hidden) at startup.
pub fn setup(app: &AppHandle) {
    let p = app.state::<State>().0.lock().unwrap().clone();
    let bar = imp::create(app, "bar", "index.html?w=bar", "scratchy-bar", BAR_W + p.gap, BAR_H, false);
    imp::place(&bar, p.right, 0, p.bar_y);
    imp::show(&bar);

    let card = imp::create(app, "card", "index.html?w=card", "scratchy-card", p.card_w, p.card_h, true);
    let (dx, dy) = default_card_pos(app, &p);
    imp::place(&card, false, p.card_x.unwrap_or(dx), p.card_y.unwrap_or(dy));
    // the card stays hidden until the bar is clicked
}

fn on_main(app: &AppHandle, f: impl FnOnce(&AppHandle) + Send + 'static) {
    let a = app.clone();
    let _ = app.run_on_main_thread(move || f(&a));
}

pub fn card(app: &AppHandle) -> Option<WebviewWindow> { app.get_webview_window("card") }

pub fn show_card(app: &AppHandle) {
    on_main(app, |app| {
        let p = app.state::<State>().0.lock().unwrap().clone();
        if let Some(c) = card(app) {
            let (dx, dy) = default_card_pos(app, &p);
            imp::place(&c, false, p.card_x.unwrap_or(dx), p.card_y.unwrap_or(dy));
            imp::resize(&c, p.card_w, p.card_h);
            imp::show(&c);
            let _ = c.set_focus();
        }
    });
}

pub fn hide_card(app: &AppHandle) {
    on_main(app, |app| { if let Some(c) = card(app) { let _ = c.hide(); } });
}

pub fn toggle_card(app: &AppHandle) {
    let visible = card(app).and_then(|c| c.is_visible().ok()).unwrap_or(false);
    if visible { hide_card(app) } else { show_card(app) }
}

/// Moves the card to an absolute position (logical px from the top-left) and remembers it.
pub fn move_card(app: &AppHandle, x: i32, y: i32) {
    let (sw, sh) = screen(app);
    let (x, y) = (x.clamp(0, (sw - 80).max(0)), y.clamp(0, (sh - 60).max(0)));
    { let st = app.state::<State>(); let mut p = st.0.lock().unwrap(); p.card_x = Some(x); p.card_y = Some(y); }
    persist(app);
    on_main(app, move |app| { if let Some(c) = card(app) { imp::place(&c, false, x, y); } });
}

pub fn resize_card(app: &AppHandle, w: i32, h: i32) {
    let (sw, sh) = screen(app);
    let (w, h) = (w.clamp(260, sw), h.clamp(220, sh));
    { let st = app.state::<State>(); let mut p = st.0.lock().unwrap(); p.card_w = w; p.card_h = h; }
    persist(app);
    on_main(app, move |app| { if let Some(c) = card(app) { imp::resize(&c, w, h); } });
}

pub fn set_pinned(app: &AppHandle, pinned: bool) {
    { let st = app.state::<State>(); st.0.lock().unwrap().pinned = pinned; }
    persist(app);
}

fn prefs_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    let d = app.path().app_data_dir().ok()?;
    std::fs::create_dir_all(&d).ok()?;
    Some(d.join("overlay.json"))
}

pub fn load_prefs(app: &AppHandle) -> Prefs {
    prefs_path(app).and_then(|p| std::fs::read_to_string(p).ok()).and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

pub fn persist(app: &AppHandle) {
    if let (Some(path), Ok(t)) = (prefs_path(app), serde_json::to_string(&prefs(app))) {
        let tmp = path.with_extension("tmp");
        if std::fs::write(&tmp, t).is_ok() { let _ = std::fs::rename(&tmp, &path); }
    }
}

pub fn prefs(app: &AppHandle) -> Prefs { app.state::<State>().0.lock().unwrap().clone() }
