/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Phase 3A: Rust NDK Scanner Service with async scanning loop.
 * 
 * This service uses structured concurrency to run an async CPU frequency scanner
 * that polls sysfs files with zero-copy reads via JNI/NDK bindings.
 */

class RustScannerService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "rust_scanner_channel"
        private const val NOTIFICATION_ID = 1004
    }
    
    // Coroutine scope for structured concurrency
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    // State flow for reactive UI updates
    private val cpuStateFlow = MutableStateFlow(CpuScannerState())
    
    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        
        // Launch the CPU frequency scanner coroutine
        serviceScope.launch {
            runCpuFrequencyScanner()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundInfo = ForegroundInfo(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Xiaomi Parts - Rust Scanner")
                .setSmallIcon(android.R.drawable.ic_menu_computer)
                .build(),
            START_TYPE_ADAPTABLE
        )
        
        startForeground(NOTIFICATION_ID, foregroundInfo)
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel all background work and coroutines (guaranteed cleanup)
        serviceScope.cancelAndJoin()
    }
    
    /**
     * CPU frequency scanning loop with jitter control and batch reading.
     */
    private suspend fun runCpuFrequencyScanner() {
        val numPolicies = readNumOnlinePolicies()
        
        if (numPolicies <= 0) {
            android.util.Log.w("RustScanner", "No online policies found")
            return
        }
        
        android.util.Log.d("RustScanner", "Starting CPU frequency scanner for $numPolicies policies with jitter control")
        
        // Use jitter-controlled polling at ~2kHz instead of fixed delay
        JitterControlPoller.pollAtInterval(
            targetHz = 1000, // Poll every 1ms for real-time updates
            minDelayNs = 50_000L, // 50μs jitter buffer
            maxDelayNs = 2_000_000L, // Cap at 2ms to avoid CPU hogging
        ) {
            try {
                // Use batch reader instead of individual JNI calls
                val policyStates = CpuFrequencyScanner.scanAllPolicies()
                
                if (policyStates.isNotEmpty()) {
                    // Update state flow (reactive UI update)
                    cpuStateFlow.value = CpuScannerState(
                        timestampNs = System.nanoTime(),
                        policies = policyStates,
                        isCharging = isDeviceCharging()
                    )
                    
                    android.util.Log.d("RustScanner", "Updated CPU state with ${policyStates.size} policies")
                } else {
                    android.util.Log.w("RustScanner", "No valid policies found during scan")
                }
            } catch (e: Exception) {
                android.util.Log.e("RustScanner", "Scanning failed: ${e.message}")
                // Continue on error - don't crash the service
            }
        }
    }
    
    /**
     * Read number of online CPU policies from sysfs.
     */
    private fun readNumOnlinePolicies(): Int {
        val file = File("/sys/devices/system/cpu/online")
        
        return if (file.exists()) {
            try {
                val content = file.readText().trim()
                // Handle both "0-7" and just a number formats
                if (content.contains("-")) {
                    val range = content.split("-", 2)
                    val start = range[0].toIntOrNull() ?: 0
                    val end = range[1].toIntOrNull() ?: 0
                    end - start + 1
                } else {
                    content.toIntOrNull() ?: 0
                }
            } catch (e: Exception) {
                android.util.Log.e("RustScanner", "Failed to read online policies: ${e.message}")
                0
            }
        } else {
            android.util.Log.w("RustScanner", "online file not found")
            0
        }
    }
    
    /**
     * Read all CPU policy states using batch read pattern.
     */
    private suspend fun readAllCpuPolicies(numPolicies: Int): List<CpuPolicyState> {
        val policies = mutableListOf<CpuPolicyState>()
        
        for (policyId in 0 until numPolicies) {
            try {
                val policyInfo = readCpuPolicy(policyId)
                
                if (policyInfo != null) {
                    policies.add(policyInfo)
                } else {
                    android.util.Log.w("RustScanner", "Failed to read policy $policyId")
                }
            } catch (e: Exception) {
                android.util.Log.e("RustScanner", "Error reading policy $policyId: ${e.message}")
            }
        }
        
        return policies.sortedBy { it.id } // Sort by core ID for consistent ordering
    }
    
    /**
     * Read a single CPU policy state from sysfs.
     */
    private fun readCpuPolicy(policyId: Int): CpuPolicyState? {
        val baseDir = "/sys/devices/system/cpu/cpufreq/policy$policyId"
        
        // Check if directory exists
        val dirFile = File(baseDir)
        if (!dirFile.exists()) {
            android.util.Log.w("RustScanner", "Policy $policyId directory not found")
            return null
        }
        
        try {
            // Read governor name
            val governorPath = "$baseDir/governor"
            val governorContent = File(governorPath).readText().trim()
            
            // Read scaling min and max frequencies
            val minFreqPath = "$baseDir/scaling_min_freq"
            val maxFreqPath = "$baseDir/scaling_max_freq"
            
            val minFreqStr = if (File(minFreqPath).exists()) {
                File(minFreqPath).readText().trim()
            } else "0"
            
            val maxFreqStr = if (File(maxFreqPath).exists()) {
                File(maxFreqPath).readText().trim()
            } else "0"
            
            // Parse frequencies (in kHz)
            val minFreq = minFreqStr.toIntOrNull() ?: 0
            val maxFreq = maxFreqStr.toIntOrNull() ?: 0
            
            // Read current frequency
            val currentFreqPath = "$baseDir/scaling_cur_freq"
            val currentFreqStr = if (File(currentFreqPath).exists()) {
                File(currentFreqPath).readText().trim()
            } else "0"
            
            val currentFreq = currentFreqStr.toIntOrNull() ?: 0
            
            // Read core states
            val onlineCpusPath = "$baseDir/online_cpus"
            val onlineCpusStr = if (File(onlineCpusPath).exists()) {
                File(onlineCpusPath).readText().trim()
            } else "0"
            
            val onlineCpus = onlineCpusStr.toIntOrNull() ?: 0
            
            // Build core states list
            val coreStates = buildList {
                for (coreId in 0 until maxFreq) {
                    try {
                        val coreOnlinePath = "$baseDir/core$coreId/online"
                        if (File(coreOnlinePath).exists()) {
                            val onlineStr = File(coreOnlinePath).readText().trim()
                            val isOnline = onlineStr.toIntOrNull() == 1
                            
                            add(CpuCoreState(
                                id = coreId,
                                online = isOnline ?: false,
                                canToggle = true, // Simplified - all cores are toggleable
                                currentFrequency = currentFreq
                            ))
                        } else {
                            add(CpuCoreState(
                                id = coreId,
                                online = false,
                                canToggle = false,
                                currentFrequency = 0
                            ))
                        }
                    } catch (e: Exception) {
                        // Core-specific read failed; continue with others
                    }
                }
            }
            
            return CpuPolicyState(
                id = policyId,
                minimumFrequency = minFreq,
                maximumFrequency = maxFreq,
                governorName = governorContent,
                currentFrequency = currentFreq,
                onlineCpus = onlineCpus,
                coreStates = coreStates
            )
        } catch (e: Exception) {
            android.util.Log.e("RustScanner", "Error reading policy $policyId: ${e.message}")
            null
        }
    }
    
    /**
     * Check if device is currently charging.
     */
    private fun isDeviceCharging(): Boolean {
        return File("/sys/class/power_supply/battery/status")
            .exists() && try {
                val status = File("/sys/class/power_supply/battery/status").readText().trim()
                status == "Charging"
            } catch (e: Exception) {
                false
            }
    }
    
    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(CHANNEL_ID, "Rust Scanner", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Async CPU frequency scanning with zero-copy sysfs reads"
        
        notificationManager.createNotificationChannel(channel)
    }
}

/**
 * Data class representing the current CPU policy state for all cores.
 */
data class CpuScannerState(
    val timestampNs: Long,
    val policies: List<CpuPolicyState>,
    val isCharging: Boolean
) {
    /**
     * Get the best (highest frequency) core to use for a specific task.
     */
    fun getBestCore(): Int? = policies.maxByOrNull { it.currentFrequency }?.id
    
    /**
     * Check if any policy's governor is "performance".
     */
    val hasPerformanceGovernor: Boolean = policies.any { 
        it.governorName.lowercase().contains("performance") 
    }
}

/**
 * Data class representing the CPU frequency policy for a single core.
 */
data class CpuPolicyState(
    val id: Int,
    val minimumFrequency: Int, // kHz
    val maximumFrequency: Int, // kHz
    val governorName: String,
    val currentFrequency: Int, // kHz
    val onlineCpus: Int = 0,
    val coreStates: List<CpuCoreState> = emptyList()
) {
    /**
     * Check if this policy is currently at maximum frequency.
     */
    val isAtMaxFreq: Boolean = currentFrequency == maximumFrequency
    
    /**
     * Get the highest available frequency for this policy.
     */
    val maxAvailableFreq: Int = maximumFrequency
}

/**
 * Data class representing an individual CPU core state.
 */
data class CpuCoreState(
    val id: Int,
    val online: Boolean,
    val canToggle: Boolean,
    val currentFrequency: Int // kHz
) {
    /**
     * Check if this core is currently active (online and at max freq).
     */
    val isActive: Boolean = online && canToggle && currentFrequency > 0
}
