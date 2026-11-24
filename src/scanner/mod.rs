/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

//! Unified scanner services with Tokio async runtime - fully Android 17 compliant.

pub mod cpu;
pub mod thermal;
pub mod touch;

/// Common utilities used across all scanners.
mod utils {
    use std::sync::{Arc, Mutex};
    
    /// Shared state wrapper for thread-safe access to scanner data.
    pub fn shared_state<T: Send + Sync + 'static>(value: T) -> Arc<Mutex<T>> {
        Arc::new(Mutex::new(value))
    }
}
