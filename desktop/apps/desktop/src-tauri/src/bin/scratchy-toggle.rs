//! What the keybinding runs: tells the running Scratchy to open or close the card, or starts Scratchy with the card
//! open. It only uses the standard library, so it starts in a millisecond instead of loading the whole webview.

#[cfg(unix)]
fn main() {
    use std::io::Write;
    let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".into());
    if let Ok(mut s) = std::os::unix::net::UnixStream::connect(std::path::Path::new(&dir).join("scratchy.sock")) {
        if s.write_all(b"toggle\n").is_ok() { return; }
    }
    // Not running: start it next to this program (`scratchy` when installed, the dev build otherwise).
    let here = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf())).unwrap_or_default();
    for name in ["scratchy", "scratchy-desktop"] {
        let app = here.join(name);
        if app.exists() {
            let _ = std::process::Command::new(app).arg("--toggle").spawn();
            return;
        }
    }
    eprintln!("Scratchy isn't installed next to scratchy-toggle");
    std::process::exit(1);
}

#[cfg(not(unix))]
fn main() {}
