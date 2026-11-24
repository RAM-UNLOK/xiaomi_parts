/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Service
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkReplacePolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xiaomi.settings.cpu.CpuControlManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay

/**
 * Phase 2A: Modern CpuControl Service using Scoped Foreground Service + WorkManager.
 * 
 * Key improvements over legacy implementation:
 * - Scoped foreground service (Android 14+) with proper startType
 * - WorkManager for periodic scanning instead of busy-wait loops
 * - Coroutine-based structured concurrency
 */

class CpuControlModernService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "cpu_control_channel"
        private const val NOTIFICATION_ID = 1001
    }
    
    private lateinit var cpuManager: CpuControlManager
    private var workRequest: WorkManager.WorkData? = null
    
    // Coroutine scope for structured concurrency
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    override fun onCreate() {
        super.onCreate()
        
        // Create notification channel (required for foreground service)
        createNotificationChannel()
        
        cpuManager = CpuControlManager(applicationContext)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+: Use START_TYPE_ADAPTABLE to allow system to adjust priority based on battery
        val foregroundInfo = ForegroundInfo(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Xiaomi Parts - CPU Control")
                .setSmallIcon(android.R.drawable.ic_menu_computer)
                .build(),
            START_TYPE_ADAPTABLE // Let system adjust priority based on battery state
        )
        
        startForeground(NOTIFICATION_ID, foregroundInfo)
        
        // Enqueue periodic scanning work (see Phase 2B)
        enqueuePeriodicScanningWork()
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel all background work and coroutines
        serviceScope.cancelAndJoin()
        
        if (workRequest != null) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(
                "cpu_scanning_work"
            )
        }
    }
    
    /**
     * Periodic scanning using WorkManager instead of busy-wait loops.
     */
    private fun enqueuePeriodicScanningWork() {
        val workRequest = PeriodicWorkRequestBuilder<CpuScanningWorker>(1_000) // Every 1 second
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30_000, // Max backoff: 30 seconds
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresBatteryLevelAbove(15)
                    .build()
            )
            .setInitialDelay(0) // Start immediately
            .build()
        
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "cpu_scanning_work",
            ExistingPeriodicWorkReplacePolicy.KEEP,
            workRequest
        )
    }
    
    /**
     * Apply a CPU policy using structured concurrency pattern.
     */
    fun applyCpuPolicy(policy: com.xiaomi.settings.services.CpuControlAidlService.CpuPolicyState) {
        serviceScope.launch {
            try {
                val success = cpuManager.applySavedConfiguration(policy)
                
                if (success) {
                    // Schedule periodic scanning to monitor the result
                    enqueuePeriodicScanningWork()
                } else {
                    // Policy application failed - might need retry or fallback
                }
            } catch (e: Exception) {
                // Handle error gracefully
            }
        }
    }
    
    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(CHANNEL_ID, "CPU Control", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Manages CPU frequency and governor settings"
        
        notificationManager.createNotificationChannel(channel)
    }
}

/**
 * Worker for periodic CPU scanning.
 */
class CpuScanningWorker(
    context: Context,
    params: WorkManager.WorkData
) : PeriodicWorkProcessor(context, params) {
    
    override suspend fun process(): Boolean {
        try {
            val cpuState = com.xiaomi.settings.cpu.CpuControlManager(applicationContext).readState()
            
            // In a real implementation, this would broadcast or update UI
            // For now, just log the state
            android.util.Log.d("CpuScanningWorker", "CPU State: $cpuState")
            
            return true
        } catch (e: Exception) {
            android.util.Log.e("CpuScanningWorker", "Scanning failed: ${e.message}")
            return false // Continue scanning on next cycle
        }
    }
}
