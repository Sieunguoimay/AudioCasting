use log::{error, info};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

const SERVICE_TYPE: &str = "_audiocast._tcp.local.";

/// mDNS service advertiser for AudioCast server discovery
pub struct Discovery {
    daemon: ServiceDaemon,
    service_fullname: Option<String>,
}

impl Discovery {
    pub fn new() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let daemon = ServiceDaemon::new()?;
        Ok(Discovery {
            daemon,
            service_fullname: None,
        })
    }

    /// Register the AudioCast service on the network
    pub fn register(
        &mut self,
        server_name: &str,
        port: u16,
        codec: &str,
        sample_rate: u32,
        channels: u8,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mut properties = HashMap::new();
        properties.insert("codec".to_string(), codec.to_string());
        properties.insert("sample_rate".to_string(), sample_rate.to_string());
        properties.insert("channels".to_string(), channels.to_string());
        properties.insert("version".to_string(), env!("CARGO_PKG_VERSION").to_string());

        let host_name = hostname::get()
            .map(|h| h.to_string_lossy().to_string())
            .unwrap_or_else(|_| "audiocast".to_string());
        let host_fqdn = format!("{}.local.", host_name);

        let service = ServiceInfo::new(
            SERVICE_TYPE,
            server_name,
            &host_fqdn,
            "",  // Let the library detect the IP
            port,
            Some(properties),
        )?;

        let fullname = service.get_fullname().to_string();
        self.daemon.register(service)?;
        self.service_fullname = Some(fullname.clone());

        info!("mDNS service registered: {} on port {}", server_name, port);
        info!("Service type: {}", SERVICE_TYPE);
        info!("Service fullname: {}", fullname);

        Ok(())
    }

    /// Unregister the service
    pub fn unregister(&mut self) {
        if let Some(ref fullname) = self.service_fullname {
            match self.daemon.unregister(fullname) {
                Ok(_) => info!("mDNS service unregistered"),
                Err(e) => error!("Failed to unregister mDNS service: {}", e),
            }
            self.service_fullname = None;
        }
    }
}

impl Drop for Discovery {
    fn drop(&mut self) {
        self.unregister();
    }
}
