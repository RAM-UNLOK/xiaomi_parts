/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

//! Phase 5D: Thermal Screen UI built entirely in Rust with compose-bridge FFI.
//! 
//! Real-time temperature visualization with reactive updates from the thermal scanner.

use android_ndk::android_support_34;
use compose_bridge::prelude::*;

/// Thermal Monitor Screen - real-time temperature display and control.
#[android_ui]
pub fn thermal_monitor_screen() -> impl IntoView {
    // Build the UI tree in pure Rust
    // This generates Kotlin composable functions at runtime
    
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(24.dp)
            .background(android_ndk::android_support_34::color::MaterialYou::surface().into())
    ) {
        // Real-time temperature gauge (would use a circular progress indicator)
        Text(
            "Thermal Monitor",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 16.dp)
        ),
        
        // Temperature reading - would be bound to thermal scanner via FFI
        LazyColumn {
            items_count = { /* dynamic */ },
            item_content = |index| {
                ThermalSensorCard(/* sensor data from Rust */)
            }
        }
    }
}

/// Individual thermal sensor card showing temperature and status.
#[android_ui]
pub fn thermal_sensor_card(sensor: RawThermalReading) -> impl IntoView {
    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(16.dp),
        children = vec![
            Row(
                vertical_alignment = Alignment.CenterVertically,
                children = vec![
                    Text(format!("{}°C", sensor.value_celsius), 
                        style = MaterialTheme.typography.headlineMedium),
                    
                    // Status indicator based on thermal status
                    CircleIndicator(sensor.status)
                ]
            ),
            
            Text(
                format!("Sensor: {}", sensor.sensor_id.split('/').last().unwrap_or("unknown")),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.into()
            )
        ]
    )
}

/// Circular indicator for thermal status (Normal/Warning/Critical).
#[android_ui]
pub fn circle_indicator(status: ThermalStatus) -> impl IntoView {
    // Would use Compose's CircularProgressIndicator with color based on status
    // For now, placeholder structure
    
    Column {
        Text(match status {
            ThermalStatus::Normal => "Normal",
            ThermalStatus::Warning => "⚠️ Warning",
            ThermalStatus::Critical => "🔥 Critical",
        }, 
        style = MaterialTheme.typography.bodyLarge)
    }
}
