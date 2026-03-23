use log::{error, info};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::server::ServerState;

/// System tray icon for the AudioCast server (Windows only).
/// Runs in a separate OS thread with its own message loop.
pub struct SystemTray {
    thread: Option<std::thread::JoinHandle<()>>,
    running: Arc<AtomicBool>,
}

impl SystemTray {
    /// Start the system tray icon. Returns immediately; the tray runs in a background thread.
    pub fn start(state: Arc<ServerState>) -> Self {
        let running = Arc::new(AtomicBool::new(true));
        let tray_running = running.clone();

        let thread = std::thread::spawn(move || {
            #[cfg(windows)]
            {
                if let Err(e) = run_tray_windows(state, tray_running) {
                    error!("System tray error: {}", e);
                }
            }
            #[cfg(not(windows))]
            {
                let _ = (state, tray_running);
                info!("System tray is only supported on Windows");
            }
        });

        info!("System tray started");
        SystemTray {
            thread: Some(thread),
            running,
        }
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        // The thread will exit on its own when the message loop ends
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
        info!("System tray stopped");
    }
}

impl Drop for SystemTray {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(windows)]
fn run_tray_windows(
    state: Arc<ServerState>,
    running: Arc<AtomicBool>,
) -> Result<(), Box<dyn std::error::Error>> {
    use std::ffi::c_void;
    use windows::Win32::Foundation::*;
    use windows::Win32::Graphics::Gdi::*;
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::Shell::*;
    use windows::Win32::UI::WindowsAndMessaging::*;
    use windows::core::*;

    const WM_TRAYICON: u32 = WM_USER + 1;
    const IDM_STATUS: u32 = 1001;
    const IDM_WEBUI: u32 = 1002;
    const IDM_EXIT: u32 = 1003;

    unsafe {
        let instance = GetModuleHandleW(None)?;

        // Register window class
        let class_name = w!("AudioCastTray");
        let wc = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            lpfnWndProc: Some(tray_wnd_proc),
            hInstance: HINSTANCE(instance.0),
            lpszClassName: class_name,
            ..Default::default()
        };
        RegisterClassExW(&wc);

        // Create hidden window for tray messages
        let hinstance = HINSTANCE(instance.0);
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            class_name,
            w!("AudioCast Tray"),
            WS_OVERLAPPEDWINDOW,
            0, 0, 0, 0,
            None,
            None,
            hinstance,
            None,
        )?;

        // Store state pointer in window user data
        let state_box = Box::new(TrayState {
            server_state: state.clone(),
            running: running.clone(),
        });
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, Box::into_raw(state_box) as isize);

        // Create tray icon
        let mut nid = NOTIFYICONDATAW {
            cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: hwnd,
            uID: 1,
            uFlags: NIF_ICON | NIF_MESSAGE | NIF_TIP,
            uCallbackMessage: WM_TRAYICON,
            hIcon: LoadIconW(None, IDI_APPLICATION)?,
            ..Default::default()
        };

        // Set tooltip
        let tip = "AudioCast Server";
        let tip_wide: Vec<u16> = tip.encode_utf16().chain(std::iter::once(0)).collect();
        let copy_len = tip_wide.len().min(nid.szTip.len());
        nid.szTip[..copy_len].copy_from_slice(&tip_wide[..copy_len]);

        if !Shell_NotifyIconW(NIM_ADD, &nid).as_bool() {
            return Err("Failed to add tray icon".into());
        }

        info!("System tray icon created");

        // Message loop
        let mut msg = MSG::default();
        while running.load(Ordering::SeqCst) {
            if PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                if msg.message == WM_QUIT {
                    break;
                }
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            } else {
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
        }

        // Cleanup
        Shell_NotifyIconW(NIM_DELETE, &nid);
        let _ = DestroyWindow(hwnd);

        // Reclaim the state box
        let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut TrayState;
        if !ptr.is_null() {
            let _ = Box::from_raw(ptr);
        }
    }

    Ok(())
}

#[cfg(windows)]
struct TrayState {
    server_state: Arc<ServerState>,
    running: Arc<AtomicBool>,
}

#[cfg(windows)]
const WM_TRAYICON: u32 = 0x0400 + 1; // WM_USER + 1
#[cfg(windows)]
const IDM_STATUS: u32 = 1001;
#[cfg(windows)]
const IDM_WEBUI: u32 = 1002;
#[cfg(windows)]
const IDM_EXIT: u32 = 1003;

#[cfg(windows)]
unsafe extern "system" fn tray_wnd_proc(
    hwnd: windows::Win32::Foundation::HWND,
    msg: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
) -> windows::Win32::Foundation::LRESULT {
    use windows::Win32::Foundation::*;
    use windows::Win32::UI::Shell::*;
    use windows::Win32::UI::WindowsAndMessaging::*;
    use windows::core::*;

    match msg {
        m if m == WM_TRAYICON => {
            let event = (lparam.0 & 0xFFFF) as u32;
            if event == WM_RBUTTONUP || event == WM_CONTEXTMENU {
                // Show context menu
                let hmenu = CreatePopupMenu().unwrap_or_default();

                // Get state for status text
                let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const TrayState;
                let status_text = if !ptr.is_null() {
                    let state = &(*ptr).server_state;
                    let count = state.client_count();
                    format!("Clients: {} | {}", count, state.config.codec.to_uppercase())
                } else {
                    "AudioCast Server".to_string()
                };

                let status_wide: Vec<u16> = status_text.encode_utf16().chain(std::iter::once(0)).collect();
                let webui_text: Vec<u16> = "Open Web UI".encode_utf16().chain(std::iter::once(0)).collect();
                let exit_text: Vec<u16> = "Exit".encode_utf16().chain(std::iter::once(0)).collect();

                let _ = AppendMenuW(hmenu, MF_STRING | MF_GRAYED, IDM_STATUS as usize, PCWSTR(status_wide.as_ptr()));
                let _ = AppendMenuW(hmenu, MF_SEPARATOR, 0, None);
                let _ = AppendMenuW(hmenu, MF_STRING, IDM_WEBUI as usize, PCWSTR(webui_text.as_ptr()));
                let _ = AppendMenuW(hmenu, MF_STRING, IDM_EXIT as usize, PCWSTR(exit_text.as_ptr()));

                let mut pt = windows::Win32::Foundation::POINT::default();
                let _ = windows::Win32::UI::WindowsAndMessaging::GetCursorPos(&mut pt);

                SetForegroundWindow(hwnd);
                TrackPopupMenu(hmenu, TPM_RIGHTALIGN | TPM_BOTTOMALIGN, pt.x, pt.y, 0, hwnd, None);
                let _ = DestroyMenu(hmenu);
            }
            LRESULT(0)
        }
        WM_COMMAND => {
            let cmd = (wparam.0 & 0xFFFF) as u32;
            match cmd {
                IDM_WEBUI => {
                    let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const TrayState;
                    if !ptr.is_null() {
                        let state = &(*ptr).server_state;
                        let url = format!("http://localhost:{}", state.config.web_port);
                        let _ = std::process::Command::new("cmd")
                            .args(["/C", "start", &url])
                            .spawn();
                    }
                }
                IDM_EXIT => {
                    let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const TrayState;
                    if !ptr.is_null() {
                        (*ptr).running.store(false, std::sync::atomic::Ordering::SeqCst);
                    }
                    PostQuitMessage(0);
                }
                _ => {}
            }
            LRESULT(0)
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}
