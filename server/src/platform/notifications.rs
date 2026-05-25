#[cfg(windows)]
use std::os::windows::process::CommandExt;

use log::{info, warn};

/// Show a Windows desktop notification
pub fn show_notification(app_name: &str, title: &str, content: &str) {
    info!("Notification from {}: {} - {}", app_name, title, content);

    #[cfg(windows)]
    {
        let script = format!(
            r#"[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime] | Out-Null
$template = @"
<toast>
    <visual>
        <binding template="ToastGeneric">
            <text>{}</text>
            <text>{}</text>
            <text>{}</text>
        </binding>
    </visual>
</toast>
"@
$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml($template)
$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("DeviceLink").Show($toast)"#,
            escape_xml(app_name),
            escape_xml(title),
            escape_xml(content)
        );

        std::thread::spawn(move || {
            let output = std::process::Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-Command", &script])
                .creation_flags(0x08000000) // CREATE_NO_WINDOW
                .output();

            match output {
                Ok(o) if !o.status.success() => {
                    let stderr = String::from_utf8_lossy(&o.stderr);
                    warn!("Toast notification failed: {}", stderr.trim());
                }
                Err(e) => warn!("Failed to spawn PowerShell for toast: {}", e),
                _ => {}
            }
        });
    }

    #[cfg(not(windows))]
    {
        let _ = (app_name, title, content);
        info!("(Toast notifications only supported on Windows)");
    }
}

fn escape_xml(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}
