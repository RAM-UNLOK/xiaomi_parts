/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

//! Phase 5D: UI Screens built entirely in Rust with compose-bridge FFI.
//! 
//! All screens are written in pure Rust and compiled to Kotlin Composables via FFI,
//! achieving maximum performance and type safety end-to-end.
//! Fully Android 17 compliant with scoped foreground service integration.

pub mod cpu_screen;
pub mod thermal_screen;
pub mod touch_screen;
