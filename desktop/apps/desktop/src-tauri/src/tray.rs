//! The tray icon (waybar shows it on Hyprland): show the card, open the notes window, quit. While a screenshot
//! waits for the card, the icon turns green, as the phone's edge bar does.

use tauri::{
    image::Image,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle,
};

const ID: &str = "scratchy";
const NORMAL: [u8; 3] = [0xe8, 0xe8, 0xe8];
const WAITING: [u8; 3] = [0x3d, 0xdc, 0x84];

/// A note card: a rounded square with three lines cut out of it, drawn 4× over and averaged for smooth edges.
fn icon(rgb: [u8; 3]) -> Image<'static> {
    const N: usize = 32;
    const S: usize = 4;
    let inside = |x: f32, y: f32| -> bool {
        let (l, t, r, b, rad) = (3.0, 3.0, 29.0, 29.0, 6.0);
        if x < l || x > r || y < t || y > b { return false }
        let cx = x.clamp(l + rad, r - rad);
        let cy = y.clamp(t + rad, b - rad);
        if (x - cx).powi(2) + (y - cy).powi(2) > rad * rad { return false }
        // The lines: two long, one short.
        let line = |y0: f32, x1: f32| y >= y0 && y <= y0 + 2.4 && x >= 8.5 && x <= x1;
        !(line(9.5, 23.5) || line(14.8, 23.5) || line(20.1, 17.5))
    };
    let mut px = vec![0u8; N * N * 4];
    for y in 0..N {
        for x in 0..N {
            let mut hits = 0;
            for sy in 0..S {
                for sx in 0..S {
                    if inside(x as f32 + (sx as f32 + 0.5) / S as f32, y as f32 + (sy as f32 + 0.5) / S as f32) { hits += 1 }
                }
            }
            let i = (y * N + x) * 4;
            px[i..i + 3].copy_from_slice(&rgb);
            px[i + 3] = (hits * 255 / (S * S)) as u8;
        }
    }
    Image::new_owned(px, N as u32, N as u32)
}

pub fn start(app: &AppHandle) -> tauri::Result<()> {
    let menu = Menu::with_items(app, &[
        &MenuItem::with_id(app, "card", "Show card", true, None::<&str>)?,
        &MenuItem::with_id(app, "open", "Open Scratchy", true, None::<&str>)?,
        &PredefinedMenuItem::separator(app)?,
        &MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?,
    ])?;
    TrayIconBuilder::with_id(ID)
        .icon(icon(NORMAL))
        .tooltip("Scratchy")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, e| match e.id().as_ref() {
            "card" => crate::overlay::toggle(app),
            "open" => crate::open_main(app),
            "quit" => app.exit(0),
            _ => {}
        })
        // Where the tray reports clicks (not with Linux's app indicators, which always show the menu).
        .on_tray_icon_event(|t, e| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = e {
                crate::overlay::toggle(t.app_handle())
            }
        })
        .build(app)?;
    Ok(())
}

/// Green while a screenshot waits.
pub fn waiting(app: &AppHandle, on: bool) {
    let a = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(t) = a.tray_by_id(ID) { let _ = t.set_icon(Some(icon(if on { WAITING } else { NORMAL }))); }
    });
}
