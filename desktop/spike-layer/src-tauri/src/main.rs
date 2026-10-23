use gtk::prelude::*;
use gtk_layer_shell::{Edge, KeyboardMode, Layer, LayerShell};
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};

fn layer_window(app: &AppHandle, label: &str, url: &str, w: f64, h: f64, left_margin: i32, top_margin: i32, keyboard: bool) {
    let win = WebviewWindowBuilder::new(app, label, WebviewUrl::App(url.into()))
        .transparent(true)
        .decorations(false)
        .shadow(false)
        .visible(false)
        .inner_size(w, h)
        .on_page_load(|w, p| eprintln!("[spike] page load {:?} {} {}", p.event(), w.label(), p.url()))
        .build()
        .expect("window");
    let gtk_win = win.gtk_window().expect("gtk window");
    eprintln!("[spike] {label}: realized before init = {}", gtk_win.is_realized());
    gtk_win.init_layer_shell();
    gtk_win.set_layer(Layer::Overlay);
    gtk_win.set_namespace(if label == "card" { "scratchy-card" } else { "scratchy-bar" });
    gtk_win.set_anchor(Edge::Left, true);
    gtk_win.set_anchor(Edge::Top, true);
    gtk_win.set_layer_shell_margin(Edge::Left, left_margin);
    gtk_win.set_layer_shell_margin(Edge::Top, top_margin);
    gtk_win.set_exclusive_zone(-1);
    gtk_win.set_keyboard_mode(if keyboard { KeyboardMode::OnDemand } else { KeyboardMode::None });
    eprintln!("[spike] {label}: is_layer_window = {}", gtk_win.is_layer_window());
    gtk_win.set_size_request(w as i32, h as i32);
    gtk_win.show_all();
    eprintln!("[spike] {label}: mapped = {}, visible = {}", gtk_win.is_mapped(), gtk_win.is_visible());
}

#[tauri::command]
fn toggle_card(app: AppHandle) {
    if let Some(c) = app.get_webview_window("card") {
        let _ = if c.is_visible().unwrap_or(false) { c.hide() } else { c.show() };
    }
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![toggle_card])
        .setup(|app| {
            let h = app.handle().clone();
            layer_window(&h, "bar", "index.html?k=bar", 20.0, 100.0, 0, 400, false);
            layer_window(&h, "card", "index.html?k=card", 360.0, 300.0, 40, 300, true);
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("run");
}
