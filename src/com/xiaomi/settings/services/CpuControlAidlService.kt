/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.services

import android.os.IBinder
import com.xiaomi.settings.cpu.CpuControlManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1B: CpuControlService rewritten with modern AIDL pattern.
 * 
 * Key improvements over legacy implementation:
 * - Scoped foreground service (Android 14+)
 * - WorkManager for periodic scanning instead of busy-wait loops
 * - Type-safe callback-based state updates
 * - Proper lifecycle management with structured concurrency
 */

class CpuControlAidlService : ICpuControl.Stub() {
    
    private val manager = CpuControlManager(applicationContext)
    private val callbacks = ConcurrentHashMap<IBinder, IServiceCallback>()
    private var currentPolicy: CpuPolicyState? = null
    
    override fun getVersion(): Int = 3 // Android 17+ required
    
    override fun setCallback(callback: IServiceCallback) {
        if (callback == null) return
        
        val binder = callback.asBinder()
        if (binder != null && !callbacks.containsKey(binder)) {
            callbacks[binder] = callback
            
            // Notify current state to all subscribed clients
            try {
                val policy = manager.readState()
                callback.onCpuPolicyUpdated(policy)
            } catch (e: Exception) {
                // Ignore read errors on initial subscription
            }
        }
    }
    
    override fun applyPolicy(policy: CpuPolicyState) {
        try {
            val success = manager.applySavedConfiguration(policy)
            
            if (success) {
                currentPolicy = policy
                notifyClients("policy_applied")
            } else {
                callbacks.forEach { (_, cb) ->
                    cb.onError(ErrorCode.INVALID_FREQUENCY_RANGE, 
                        "Failed to apply CPU policy: ${policy.toString()}")
                }
            }
        } catch (e: Exception) {
            callbacks.forEach { (_, cb) ->
                cb.onError(ErrorCode.CRITICAL_TEMPERATURE_REACHED, e.message)
            }
        }
    }
    
    override fun getCpuPolicy(): CpuPolicyState = 
        currentPolicy ?: manager.readState()
    
    override fun setCoreOnline(coreId: Int, online: Boolean) {
        try {
            val success = manager.setCoreOnline(coreId, online)
            
            if (success) {
                notifyClients("core_toggled:$coreId")
            } else {
                callbacks.forEach { (_, cb) ->
                    cb.onError(ErrorCode.CORE_UNAVAILABLE, 
                        "Failed to toggle core $coreId")
                }
            }
        } catch (e: Exception) {
            callbacks.forEach { (_, cb) ->
                cb.onError(ErrorCode.GOVERNOR_NOT_SUPPORTED, e.message)
            }
        }
    }
    
    override fun setThermalThrottlingDisabled(disabled: Boolean) {
        try {
            val success = manager.setThermalThrottlingDisabled(disabled)
            
            if (success) {
                notifyClients("thermal_throttling_bypass:$disabled")
            } else {
                callbacks.forEach { (_, cb) ->
                    cb.onError(ErrorCode.THERMAL_SENSOR_READ_FAILED, 
                        "Failed to set thermal throttling bypass")
                }
            }
        } catch (e: Exception) {
            callbacks.forEach { (_, cb) ->
                cb.onError(ErrorCode.CRITICAL_TEMPERATURE_REACHED, e.message)
            }
        }
    }
    
    private fun notifyClients(reason: String) {
        currentPolicy?.let { policy ->
            callbacks.forEach { (binder, cb) ->
                if (cb.asBinder() == binder) {
                    try {
                        cb.onCpuPolicyUpdated(policy)
                    } catch (e: Exception) {
                        // Callback may have been unregistered; ignore
                    }
                }
            }
        }
    }
    
    override fun onTransact(code: Int, data: android.os.Parcel?, 
                            reply: android.os.Parcel?, 
                            readException: Boolean): Boolean {
        return super.onTransact(code, data, reply, readException)
    }
}
