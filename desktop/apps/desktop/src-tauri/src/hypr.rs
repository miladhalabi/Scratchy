//! Blur behind the card on Hyprland, only under the card itself: the surface covers the screen, but everything
//! outside the card is fully clear, and `ignore_alpha` keeps Hyprland from blurring clear pixels.
//! The rules are set at runtime with hyprctl (your config files are not touched), and set again after a
//! config reload, which drops them.

use std::{io::{BufRead, BufReader}, process::Command, sync::Mutex, thread, time::Duration};

static WANTED: Mutex<Option<bool>> = Mutex::new(None);
static APPLIED: Mutex<Option<bool>> = Mutex::new(None);

pub fn running() -> bool { std::env::var("HYPRLAND_INSTANCE_SIGNATURE").is_ok_and(|s| !s.is_empty()) }

fn apply() {
    if !running() { return }
    let Some(on) = *WANTED.lock().unwrap() else { return };
    let mut applied = APPLIED.lock().unwrap();
    if *applied == Some(on) { return }
    let batch = if on {
        "keyword layerrule blur on, match:namespace scratchy ; keyword layerrule ignore_alpha 0.1, match:namespace scratchy"
    } else {
        "keyword layerrule blur off, match:namespace scratchy"
    };
    if Command::new("hyprctl").args(["--batch", batch]).output().is_ok_and(|o| o.status.success()) { *applied = Some(on) }
}

/// Watches Hyprland's event socket for config reloads.
pub fn start() {
    if !running() { return }
    thread::spawn(|| loop {
        let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_default();
        let sig = std::env::var("HYPRLAND_INSTANCE_SIGNATURE").unwrap_or_default();
        if let Ok(s) = std::os::unix::net::UnixStream::connect(format!("{dir}/hypr/{sig}/.socket2.sock")) {
            for line in BufReader::new(s).lines().map_while(Result::ok) {
                if line.starts_with("configreloaded>>") { *APPLIED.lock().unwrap() = None; apply(); }
            }
        }
        thread::sleep(Duration::from_secs(5));
    });
}

/// On when the card is see-through and blur is wanted.
#[tauri::command]
pub async fn card_blur(on: bool) {
    *WANTED.lock().unwrap() = Some(on);
    apply();
}

#[tauri::command]
pub async fn hyprland() -> bool { running() }
