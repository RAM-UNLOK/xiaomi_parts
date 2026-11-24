/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.services

/**
 * Phase 1A: Unified AIDL Base Interface for all services
 * 
 * Android 17+ requirement: All services must support version negotiation
 * and callback-based RPCs for reactive state updates.
 */

// ============================================================================
// Core AIDL Interfaces (Base)
// ============================================================================

/**
 * Base interface for all Xiaomi Parts services.
 * Every service MUST implement this to enable Android 17's adaptive features.
 */
interface IServicesBase {
    /**
     * Service version for backward compatibility.
     * Android 17+ requires version negotiation on connection.
     */
    fun getVersion(): Int
    
    /**
     * Register a callback listener for reactive state updates.
     * This replaces manual BroadcastReceivers with type-safe RPC callbacks.
     */
    fun setCallback(callback: IServiceCallback)
}

/**
 * Type-safe callback interface for all services.
 * Replaces untyped BroadcastReceivers and SharedPreferences listeners.
 */
interface IServiceCallback {
    /**
     * Called when thermal state changes (e.g., temperature exceeds threshold).
     * @param state Current thermal profile/state
     * @param timestampNs Nanosecond-precision timestamp for ordering events
     */
    fun onThermalStateUpdated(state: ThermalState, timestampNs: Long)
    
    /**
     * Called when a thermal profile is applied.
     * @param profileId The new profile ID
     * @param reason Why the profile was applied (e.g., "screen_off", "app_foreground")
     */
    fun onProfileChanged(profileId: Int, reason: String)
    
    /**
     * Called when an error occurs during service operation.
     * @param errorCode The error code from ErrorCode enum
     * @param errorMessage Human-readable description (for logging/UI)
     */
    fun onError(errorCode: ErrorCode, errorMessage: String?)
}

// ============================================================================
// Domain-Specific AIDL Interfaces
// ============================================================================

/**
 * CPU Control service - manages frequency governor policies and core states.
 * Replaces manual Binder/Parcel calls with type-safe FFI.
 */
interface ICpuControl : IServicesBase {
    /**
     * Current CPU policy state (read-only for clients).
     */
    data class CpuPolicyState(
        val id: Int,
        val minimumFrequency: Long, // kHz
        val maximumFrequency: Long, // kHz  
        val governorName: String,
        val coreStates: List<CpuCoreState>
    ) {
        /** Individual CPU core state for per-core frequency control */
        data class CpuCoreState(
            val id: Int,
            val online: Boolean,
            val canToggle: Boolean,
            val currentFrequency: Long // kHz
        )
    }
    
    /**
     * Apply a new CPU policy.
     * @param policy The desired frequency and governor settings
     */
    fun applyPolicy(policy: CpuPolicyState)
    
    /**
     * Get the current CPU policy state (read-only).
     */
    fun getCpuPolicy(): CpuPolicyState
    
    /**
     * Toggle a specific core online/offline.
     * @param coreId The core index to toggle
     * @param online Whether to bring core online or offline it
     */
    fun setCoreOnline(coreId: Int, online: Boolean)
    
    /**
     * Set thermal throttling bypass (bypass OS-level thermal protection).
     * @param disabled If true, ignore thermal throttling limits
     */
    fun setThermalThrottlingDisabled(disabled: Boolean)
}

/**
 * Touch Sampling service - manages high touch sampling rate (HTSR) state.
 * Replaces manual JNI calls with type-safe AIDL contract.
 */
interface ITouchSampling : IServicesBase {
    /**
     * Current touch sampling configuration.
     */
    data class TouchState(
        val mode: Int,           // Mode index (e.g., GAME_MODE, HIGH_RATE)
        val rateHz: Int,         // Polling rate in Hz
        val sensitivity: Float,  // Finger sensitivity (0-99)
        val reportRateHz: Int    // Touch report rate
    )
    
    /**
     * Apply touch sampling configuration.
     * @param mode Mode to apply (e.g., HIGH_RATE = 1)
     * @param sensitivity Sensitivity level (0-99, higher = more sensitive)
     * @param reportRateHz Report rate in Hz (e.g., 34 ≈ 240Hz)
     */
    fun setTouchMode(mode: Int, sensitivity: Int, reportRateHz: Int)
    
    /**
     * Get current touch sampling state.
     */
    fun getTouchState(): TouchState
    
    /**
     * Enable/disable HTSR boost mode.
     * @param enabled If true, apply high polling rate + max sensitivity
     */
    fun setBoostMode(enabled: Boolean)
}

/**
 * Thermal monitoring service - manages thermal profiles and sensor reading pipeline.
 * Replaces manual sysfs file I/O with typed callbacks.
 */
interface ITermal : IServicesBase {
    /**
     * Current thermal state/profile.
     */
    data class ThermalState(
        val id: Int,
        val name: String,
        val temperatureCelsius: Float,
        val criticalThreshold: Float,
        val activeCoolingMode: String? // e.g., "none", "fan_boost", "cpu_limit"
    )
    
    /**
     * Request thermal state updates (subscribe to changes).
     * @param callback Listener for temperature updates
     */
    fun subscribeThermal(callback: (Float) -> Unit)
    
    /**
     * Unsubscribe from thermal updates.
     */
    fun unsubscribeThermal()
    
    /**
     * Apply a specific thermal profile.
     * @param profileId The ID of the profile to apply
     */
    fun applyProfile(profileId: Int, packageName: String?) // package name for per-app profiling
    
    /**
     * Get current temperature reading (immediate, not subscription).
     */
    fun getTemperature(): ThermalState
}

// ============================================================================
// Error Codes - Type-safe error propagation
// ============================================================================
enum class ErrorCode(val description: String) {
    SUCCESS("Operation completed successfully"),
    
    // CPU Control errors
    INVALID_FREQUENCY_RANGE("Minimum frequency cannot exceed maximum"),
    GOVERNOR_NOT_SUPPORTED("Requested governor is not available on this device"),
    CORE_UNAVAILABLE("CPU core is offline or in critical state"),
    
    // Touch Sampling errors  
    TOUCH_AIDL_NOT_AVAILABLE("Xiaomi TouchFeature AIDL service is unavailable"),
    RATE_MODE_CONFLICT("Cannot set rate and mode simultaneously"),
    
    // Thermal errors
    THERMAL_SENSOR_READ_FAILED("Failed to read from thermal sensor sysfs file"),
    PROFILE_WRITE_FAILED("Failed to write thermal profile configuration"),
    CRITICAL_TEMPERATURE_REACHED("Device temperature exceeded critical threshold")
}
