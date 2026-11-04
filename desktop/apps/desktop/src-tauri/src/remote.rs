//! `scratchy --toggle` from a keybinding: the new process hands the request to the running app over a local socket
//! and exits before any window or webview starts, so the shortcut feels instant.

use tauri::{AppHandle, Manager};

#[cfg(unix)]
fn path() -> std::path::PathBuf {
    let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".into());
    std::path::Path::new(&dir).join("scratchy.sock")
}

/// True when a running Scratchy took the request.
#[cfg(unix)]
pub fn send(cmd: &str) -> bool {
    use std::io::Write;
    let Ok(mut s) = std::os::unix::net::UnixStream::connect(path()) else { return false };
    s.write_all(format!("{cmd}\n").as_bytes()).is_ok()
}
#[cfg(not(unix))]
pub fn send(_cmd: &str) -> bool { false }

#[cfg(unix)]
pub fn listen(app: AppHandle) {
    use std::io::{BufRead, BufReader};
    let p = path();
    let _ = std::fs::remove_file(&p);            // a leftover from a crash; send() above found nobody listening
    let Ok(l) = std::os::unix::net::UnixListener::bind(&p) else { return };
    std::thread::spawn(move || {
        for s in l.incoming().flatten() {
            let mut line = String::new();
            if BufReader::new(s).read_line(&mut line).is_err() { continue; }
            match line.trim() {
                "toggle" => crate::overlay::toggle(&app),
                _ => if let Some(w) = app.get_webview_window("main") { let _ = w.show(); let _ = w.set_focus(); },
            }
        }
    });
}
#[cfg(not(unix))]
pub fn listen(_app: AppHandle) {}
