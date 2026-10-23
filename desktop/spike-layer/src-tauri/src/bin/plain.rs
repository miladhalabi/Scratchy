use gtk::prelude::*;
use gtk_layer_shell::{Edge, Layer, LayerShell};

fn main() {
    gtk::init().unwrap();
    let css = gtk::CssProvider::new();
    css.load_from_data(b"window { background: #ff0000; }").unwrap();
    gtk::StyleContext::add_provider_for_screen(&gdk::Screen::default().unwrap(), &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    let win = gtk::Window::new(gtk::WindowType::Toplevel);
    if std::env::var("NOLAYER").is_err() {
        win.init_layer_shell();
        win.set_layer(Layer::Overlay);
        win.set_namespace("scratchy-plain");
        win.set_anchor(Edge::Left, true);
        win.set_anchor(Edge::Top, true);
        win.set_layer_shell_margin(Edge::Left, 40);
        win.set_layer_shell_margin(Edge::Top, 300);
    }
    win.connect_draw(|_, _| { eprintln!("[plain] draw"); gtk::glib::Propagation::Proceed });
    if std::env::var("V_REQ").is_ok() { win.set_size_request(360, 300); } else { win.set_default_size(360, 300); }
    if std::env::var("V_ZONE").is_ok() { win.set_exclusive_zone(-1); }
    if std::env::var("V_KB").is_ok() { win.set_keyboard_mode(gtk_layer_shell::KeyboardMode::None); }
    if std::env::var("V_TOP").is_ok() { win.set_layer(Layer::Top); }
    win.set_app_paintable(true);
    win.connect_draw(|_, cr| { cr.set_source_rgb(1.0, 0.0, 0.0); cr.paint().unwrap(); gtk::glib::Propagation::Proceed });
    win.connect_map(|_| eprintln!("[plain] mapped"));
    win.show_all();
    gtk::main();
}
