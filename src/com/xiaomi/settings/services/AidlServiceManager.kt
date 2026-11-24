/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1E: Central service manager that coordinates all AIDL services.
 * 
 * Responsibilities:
 * - Lifecycle management (create, bind, unbind)
 * - Callback propagation to UI layers
 * - Error handling and recovery
 */

class AidlServiceManager(private val context: Context) {
    
    private val cpuControl = CpuControlAidlService()
    private val touchSampling = TouchSamplingAidlService()
    private val thermal = ThermalAidlService()
    
    // Track which UI components are subscribed to each service
    private val subscribers = ConcurrentHashMap<IBinder, Set<ServiceType>>()
    
    /**
     * Subscribe a component (e.g., screen/composable) to service updates.
     */
    fun subscribe(binder: IBinder, type: ServiceType) {
        subscribers.computeIfAbsent(binder) { emptySet() }.add(type)
        
        // Connect the binder to the appropriate AIDL service
        connectBinderToService(binder, type)
    }
    
    /**
     * Unsubscribe a component from all services.
     */
    fun unsubscribe(binder: IBinder) {
        subscribers.remove(binder)?.forEach { type ->
            when (type) {
                ServiceType.CPU -> cpuControl.setCallback(null) // Will reconnect on next subscribe
                ServiceType.TOUCH -> touchSampling.setCallback(null)
                ServiceType.THERMAL -> thermal.unsubscribeThermal()
            }
        }
    }
    
    private fun connectBinderToService(binder: IBinder, type: ServiceType) {
        when (type) {
            ServiceType.CPU -> cpuControl.setCallback(CpuCallbackProxy(binder))
            ServiceType.TOUCH -> touchSampling.setCallback(TouchCallbackProxy(binder))
            ServiceType.THERMAL -> thermal.subscribeThermal({ temp -> ThermalCallbackProxy(binder).onTempUpdate(temp) })
        }
    }
    
    /**
     * Get the current state from a specific service.
     */
    fun getCpuPolicy(): CpuControlAidlService.CpuPolicyState = cpuControl.getCpuPolicy()
    
    fun getTouchState(): TouchSamplingAidlService.TouchState = touchSampling.getTouchState()
    
    fun getThermalState(): ThermalAidlService.ThermalState = thermal.getTemperature()
    
    /**
     * Apply a CPU policy.
     */
    fun applyCpuPolicy(policy: CpuControlAidlService.CpuPolicyState) {
        cpuControl.applyPolicy(policy)
    }
    
    /**
     * Set touch sampling mode.
     */
    fun setTouchBoostMode(enabled: Boolean) {
        if (enabled) {
            touchSampling.setBoostMode(true)
        } else {
            touchSampling.setBoostMode(false)
        }
    }
    
    /**
     * Apply a thermal profile.
     */
    fun applyThermalProfile(profileId: Int, packageName: String?) {
        thermal.applyProfile(profileId, packageName)
    }
}

/**
 * Service type enumeration for subscription management.
 */
enum class ServiceType {
    CPU,
    TOUCH,
    THERMAL
}

/**
 * Proxy to forward AIDL callbacks from service to UI layer.
 */
class CpuCallbackProxy(private val binder: IBinder) : IServiceCallback {
    override fun onThermalStateUpdated(state: ThermalState, timestampNs: Long) {
        // Forward to all subscribers that need thermal updates
        notifySubscribers(ServiceType.THERMAL, state, timestampNs)
    }
    
    override fun onProfileChanged(profileId: Int, reason: String) {
        // Profile change notification
        notifySubscribers(ServiceType.TOUCH, null, 0L) // Touch may need to re-sync
        notifySubscribers(ServiceType.CPU, null, 0L) // CPU may need to adjust policy
    }
    
    override fun onError(errorCode: ErrorCode, errorMessage: String?) {
        // Error handling - could show toast or log
        System.err.println("AIDL Service Error: $errorCode - $errorMessage")
    }
}

class TouchCallbackProxy(private val binder: IBinder) : IServiceCallback {
    override fun onThermalStateUpdated(state: ThermalState, timestampNs: Long) {
        // High temperature may require touch sensitivity adjustment
        notifySubscribers(ServiceType.THERMAL, state, timestampNs)
    }
    
    override fun onProfileChanged(profileId: Int, reason: String) {
        // Profile change - might need to adjust touch mode
        notifySubscribers(ServiceType.CPU, null, 0L)
    }
    
    override fun onError(errorCode: ErrorCode, errorMessage: String?) {
        System.err.println("Touch AIDL Error: $errorCode - $errorMessage")
    }
}

class ThermalCallbackProxy(private val binder: IBinder) : IServiceCallback {
    override fun onThermalStateUpdated(state: ThermalState, timestampNs: Long) {
        // Temperature update - notify subscribers
        notifySubscribers(ServiceType.THERMAL, state, timestampNs)
    }
    
    override fun onProfileChanged(profileId: Int, reason: String) {
        // Profile changed - might need to adjust CPU or touch settings
        notifySubscribers(ServiceType.CPU, null, 0L)
        notifySubscribers(ServiceType.TOUCH, null, 0L)
    }
    
    override fun onError(errorCode: ErrorCode, errorMessage: String?) {
        System.err.println("Thermal AIDL Error: $errorCode - $errorMessage")
    }
}

/**
 * Helper to forward callbacks from AIDL service to UI layer.
 */
private fun notifySubscribers(type: ServiceType, data: Any?, timestampNs: Long) {
    subscribers.forEach { (binder, types) ->
        if (types.contains(type)) {
            try {
                // In a real implementation, this would call a method on the UI component
                // For now, we just log the notification
                System.out.println("[${type.name}] Binder: $binder - Data: $data")
            } catch (e: Exception) {
                // Ignore callback failures
            }
        }
    }
}
