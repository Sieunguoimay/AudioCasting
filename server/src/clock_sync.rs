use crate::protocol::{now_us, ClockSyncMessage};
use log::{debug, info};
use std::collections::HashMap;
use parking_lot::RwLock;
use std::sync::Arc;

/// Per-client clock synchronization state
#[derive(Debug, Clone)]
pub struct ClientClockState {
    /// Estimated clock offset (client_time - server_time) in microseconds
    pub offset_us: i64,
    /// Estimated round-trip time in microseconds
    pub rtt_us: u64,
    /// Number of sync exchanges completed
    pub sync_count: u32,
    /// Exponential moving average weight
    alpha: f64,
}

impl ClientClockState {
    pub fn new() -> Self {
        ClientClockState {
            offset_us: 0,
            rtt_us: 0,
            sync_count: 0,
            alpha: 0.3, // EMA weight — higher = more responsive, lower = more stable
        }
    }

    /// Process a completed clock sync exchange.
    /// t1: server send time, t2: client receive time, t3: client send time, t4: server receive time
    pub fn update(&mut self, t1: u64, t2: u64, t3: u64, t4: u64) {
        let rtt = (t4 as i64 - t1 as i64) - (t3 as i64 - t2 as i64);
        let offset = ((t2 as i64 - t1 as i64) + (t3 as i64 - t4 as i64)) / 2;

        if self.sync_count == 0 {
            self.rtt_us = rtt.unsigned_abs();
            self.offset_us = offset;
        } else {
            // Exponential moving average
            self.rtt_us = (self.alpha * rtt.unsigned_abs() as f64
                + (1.0 - self.alpha) * self.rtt_us as f64) as u64;
            self.offset_us = (self.alpha * offset as f64
                + (1.0 - self.alpha) * self.offset_us as f64) as i64;
        }

        self.sync_count += 1;
        debug!(
            "Clock sync #{}: offset={}us, rtt={}us",
            self.sync_count, self.offset_us, self.rtt_us
        );
    }

    /// Convert a server timestamp to the client's clock domain
    pub fn server_to_client_time(&self, server_time_us: u64) -> u64 {
        (server_time_us as i64 + self.offset_us) as u64
    }
}

/// Manages clock sync state for all connected clients
pub struct ClockSyncManager {
    clients: Arc<RwLock<HashMap<String, ClientClockState>>>,
}

impl ClockSyncManager {
    pub fn new() -> Self {
        ClockSyncManager {
            clients: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn add_client(&self, client_id: &str) {
        self.clients.write().insert(client_id.to_string(), ClientClockState::new());
        info!("Clock sync: added client {}", client_id);
    }

    pub fn remove_client(&self, client_id: &str) {
        self.clients.write().remove(client_id);
        info!("Clock sync: removed client {}", client_id);
    }

    /// Create a clock sync request to send to a client
    pub fn create_sync_request(&self) -> ClockSyncMessage {
        ClockSyncMessage {
            t1: now_us(),
            t2: 0,
            t3: 0,
        }
    }

    /// Process a clock sync response from a client
    pub fn process_sync_response(&self, client_id: &str, msg: &ClockSyncMessage) {
        let t4 = now_us();
        let mut clients = self.clients.write();
        if let Some(state) = clients.get_mut(client_id) {
            state.update(msg.t1, msg.t2, msg.t3, t4);
        }
    }

    pub fn get_client_state(&self, client_id: &str) -> Option<ClientClockState> {
        self.clients.read().get(client_id).cloned()
    }

    pub fn get_all_states(&self) -> HashMap<String, ClientClockState> {
        self.clients.read().clone()
    }
}
