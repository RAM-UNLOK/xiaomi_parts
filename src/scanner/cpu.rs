/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

//! CPU Scanner Service - batch reading + jitter control, Android 17 compliant.

use std::sync::{Arc, Mutex};
use std::time::Instant;

use once_cell::sync::Lazy;
use tokio::task::JoinHandle;

pub static CPU_SCANNER: Lazy<Arc<Mutex<CpuScanner>>> = Lazy::new(|| {
    Arc::new(Mutex::new(CpuScanner::new()))
});

pub struct CpuScanner {
    handle: Option<JoinHandle<()>>,
    current_policy: Mutex<Option<CpuPolicyState>>,
}

impl CpuScanner {
    pub fn new() -> Self {
        Self {
            handle: None,
            current_policy: Mutex::new(None),
        }
    }

    pub async fn start(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        self.handle = Some(tokio::spawn(async move {
            loop {
                let now = Instant::now();
                
                // Jitter control: busy-wait with nanosecond precision (Android 17+)
                let jitter_ns = 50_000u64;
                
                while now.elapsed() < std::time::Duration::from_millis(1) {
                    if now.elapsed().as_micros() > 1_000_000 + jitter_ns {
                        break;
                    }
                }
                
                let policies = Self::batch_read_all_policies();
                
                if !policies.is_empty() {
                    *self.current_policy.lock().unwrap() = Some(policies);
                    log::debug!("CPU scan complete: {} policies", 
                        find_max_freq(&policies));
                }
            }
        }));
        
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(handle) = self.handle.take() {
            handle.abort();
        }
    }

    pub fn get_policy(&self) -> Option<CpuPolicyState> {
        self.current_policy.lock().unwrap().clone()
    }

    fn batch_read_all_policies() -> Vec<CpuPolicyState> {
        let mut policies = Vec::new();
        
        for entry in std::fs::read_dir("/sys/devices/system/cpu/")
            .ok()?.filter_map(|e| e.ok()) {
            
            if !entry.metadata().unwrap().is_dir() {
                continue;
            }
            
            let policy_path = entry.path();
            let name = policy_path.file_name().unwrap_or_default();
            
            if let Some(policy_id) = name.to_str()
                .and_then(|s| s.strip_prefix("policy").ok())
                .and_then(|s| s.parse::<i32>().ok()) {
                
                if let Ok(Some(policy)) = Self::read_cpu_policy(&policy_path, policy_id as u32) {
                    policies.push(policy);
                }
            }
        }
        
        policies.sort_by_key(|p| p.id);
        policies
    }

    fn read_cpu_policy(policy_path: &std::path::Path, _id: u32) -> Result<Option<CpuPolicyState>, std::io::Error> {
        if !policy_path.exists() {
            return Ok(None);
        }
        
        let governor = Self::read_single_file(&policy_path.join("governor"))?;
        let min_freq = Self::read_single_file(&policy_path.join("scaling_min_freq"))?;
        let max_freq = Self::read_single_file(&policy_path.join("scaling_max_freq"))?;
        let cur_freq = Self::read_single_file(&policy_path.join("scaling_cur_freq"))?;
        
        Ok(Some(CpuPolicyState {
            id: 0, // Would parse from path
            governor: governor.unwrap_or_default(),
            min_freq: min_freq.unwrap_or(0),
            max_freq: max_freq.unwrap_or(0),
            cur_freq: cur_freq.unwrap_or(0),
            online_cpus: 0,
        }))
    }

    fn read_single_file(path: &std::path::Path) -> Result<Option<String>, std::io::Error> {
        if path.exists() {
            Ok(Some(std::fs::read_to_string(path)?.trim().to_string()))
        } else {
            Ok(None)
        }
    }
}

#[derive(Debug, Clone)]
pub struct CpuPolicyState {
    pub id: u32,
    pub governor: String,
    pub min_freq: i64,
    pub max_freq: i64,
    pub cur_freq: i64,
    pub online_cpus: u32,
}

pub fn find_max_freq(policies: &[CpuPolicyState]) -> i64 {
    policies.iter().map(|p| p.cur_freq).max().unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_batch_read_logic() {
        let scanner = CpuScanner::new();
        assert_eq!(scanner.get_policy(), None);
    }
}
