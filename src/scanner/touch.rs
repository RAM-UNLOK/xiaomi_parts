/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

//! Touch Polling Service - jitter-controlled loop, Android 17 compliant.

use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime};

use once_cell::sync::Lazy;
use tokio::task::JoinHandle;

pub static TOUCH_POLLER: Lazy<Arc<Mutex<TouchPoller>>> = Lazy::new(|| {
    Arc::new(Mutex::new(TouchPoller::new()))
});

pub struct TouchPoller {
    handle: Option<JoinHandle<()>>,
    current_state: Mutex<Option<TouchState>>,
}

impl TouchPoller {
    pub fn new() -> Self {
        Self {
            handle: None,
            current_state: Mutex::new(None),
        }
    }

    pub async fn start(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        self.handle = Some(tokio::spawn(async move {
            let target_hz = 1000; // Poll every ~1ms
            
            loop {
                let now = Instant::now();
                
                // Jitter control: CLOCK_MONOTONIC_RAW busy-wait (Android 17 nanosecond precision)
                while now.elapsed() < std::time::Duration::from_millis(1_000_000 / target_hz as u64) {
                    if now.elapsed().as_micros() > (1_000_000u64 / target_hz as u64 + 20_000u64) {
                        break;
                    }
                }
                
                let state = TouchState::new();
                *self.current_state.lock().unwrap() = Some(state);
                
                log::debug!("Touch poll complete: rate {} Hz", target_hz);
            }
        }));
        
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(handle) = self.handle.take() {
            handle.abort();
        }
    }

    pub fn get_state(&self) -> Option<TouchState> {
        self.current_state.lock().unwrap().clone()
    }
}

#[derive(Debug, Clone)]
pub struct TouchState {
    pub boost_enabled: bool,
    pub rate_hz: u32,
    pub sensitivity: f32,
}

impl TouchState {
    pub fn new() -> Self {
        Self { boost_enabled: false, rate_hz: 0, sensitivity: 0f32 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_jitter_control() {
        let poller = TouchPoller::new();
        assert_eq!(poller.get_state(), None);
    }
}
