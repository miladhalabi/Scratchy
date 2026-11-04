//! `scratchy --toggle` from a keybinding: the new process hands the request to the running app over a local socket
//! and exits before any window or webview starts, so the shortcut feels instant.

use tauri::{AppHandle, Manager};

#[cfg(unix)]
fn path() -> std::path::PathBuf {
    let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".into());
    std::path::Path::new(&dir).join("scratchy.sock")
}

/// Milliseconds since this process was started by the kernel (Linux), for the timing logs.
#[cfg(target_os = "linux")]
pub fn since_exec_ms() -> f64 {
    let stat = std::fs::read_to_string("/proc/self/stat").unwrap_or_default();
    let start: f64 = stat.rsplit(')').next().unwrap_or("").split_whitespace().nth(19).and_then(|v| v.parse().ok()).unwrap_or(0.0);
    let up: f64 = std::fs::read_to_string("/proc/uptime").ok()
        .and_then(|u| u.split_whitespace().next().and_then(|v| v.parse().ok())).unwrap_or(0.0);
    (up - start / 100.0) * 1000.0
}
#[cfg(not(target_os = "linux"))]
pub fn since_exec_ms() -> f64 { 0.0 }

fn now_ms() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs_f64() * 1000.0).unwrap_or(0.0)
}

/// True when a running Scratchy took the request.
#[cfg(unix)]
pub fn send(cmd: &str) -> bool {
    use std::io::Write;
    let Ok(mut s) = std::os::unix::net::UnixStream::connect(path()) else { return false };
    // The time the keybinding started this process, so the running app can log the whole delay.
    let started = now_ms() - since_exec_ms();
    s.write_all(format!("{cmd} {started:.1}\n").as_bytes()).is_ok()
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
            let mut parts = line.split_whitespace();
            let cmd = parts.next().unwrap_or("");
            if let Some(t) = parts.next().and_then(|v| v.parse::<f64>().ok()) {
                eprintln!("[perf] request arrived {:.1} ms after the key started `scratchy`", now_ms() - t);
                crate::overlay::KEY_AT.store((t * 10.0) as u64, std::sync::atomic::Ordering::SeqCst);
            }
            match cmd {
                "toggle" => crate::overlay::toggle(&app),
                _ => if let Some(w) = app.get_webview_window("main") { let _ = w.show(); let _ = w.set_focus(); },
            }
        }
    });
}
#[cfg(not(unix))]
pub fn listen(_app: AppHandle) {}
