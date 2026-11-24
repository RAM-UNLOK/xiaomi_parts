/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings

import android.app.PendingIntent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkReplacePolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * Phase 2C: Modern Thermal Service using WorkManager + Scoped Foreground.
 * 
 * Key improvements over legacy implementation:
 * - Uses WorkManager for periodic temperature reading instead of manual polling
 * - Structured concurrency with coroutine scope
 * - Proper foreground service type declaration
 */

class ThermalModernService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "thermal_channel"
        private const val NOTIFICATION_ID = 1003
    }
    
    private lateinit var thermalUtils: com.xiaomi.settings.thermal.ThermalUtils
    private var workRequest: WorkManager.WorkData? = null
    
    // Coroutine scope for structured concurrency
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    override fun onCreate() {
        super.onCreate()
        
        thermalUtils = com.xiaomi.settings.thermal.ThermalUtils.getInstance(applicationContext)
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundInfo = ForegroundInfo(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Xiaomi Parts - Thermal Monitoring")
                .setSmallIcon(android.R.drawable.ic_menu_computer)
                .build(),
            START_TYPE_ADAPTABLE // Let system adjust priority based on battery
        )
        
        startForeground(NOTIFICATION_ID, foregroundInfo)
        
        // Enqueue periodic temperature monitoring work
        enqueuePeriodicMonitoringWork()
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        serviceScope.cancelAndJoin()
        
        if (workRequest != null) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("thermal_monitoring_work")
        }
    }
    
    /**
     * Periodic temperature monitoring using WorkManager.
     */
    private fun enqueuePeriodicMonitoringWork() {
        val workRequest = PeriodicWorkRequestBuilder<ThermalMonitorWorker>(500) // Every 500ms
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10_000, // Max backoff: 10 seconds
                Constraints.Builder()
                    .setRequiresBatteryLevelAbove(20)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "thermal_monitoring_work",
            ExistingPeriodicWorkReplacePolicy.KEEP,
            workRequest
        )
    }
    
    /**
     * Apply a thermal profile (e.g., for screen-off or specific app).
     */
    fun applyThermalProfile(profileId: Int, packageName: String?) {
        serviceScope.launch {
            try {
                val success = thermalUtils.setThermalProfile(packageName ?: "")
                
                if (!success) {
                    android.util.Log.e("ThermalModern", "Failed to apply profile $profileId")
                }
            } catch (e: Exception) {
                android.util.Log.e("ThermalModern", "Apply profile failed: ${e.message}")
            }
        }
    }
    
    /**
     * Get current temperature reading.
     */
    fun getTemperature(): Float = try {
        thermalUtils.getTemperatureCelsius()
    } catch (e: Exception) {
        android.util.Log.e("ThermalModern", "Failed to read temperature: ${e.message}")
        0f
    }
    
    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(CHANNEL_ID, "Thermal Monitoring", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Monitors device temperature and applies thermal profiles"
        
        notificationManager.createNotificationChannel(channel)
    }
}

/**
 * Worker for periodic thermal monitoring.
 */
class ThermalMonitorWorker(
    context: Context,
    params: WorkManager.WorkData
) : PeriodicWorkProcessor(context, params) {
    
    override suspend fun process(): Boolean {
        try {
            val temp = com.xiaomi.settings.thermal.ThermalUtils.getTemperatureCelsius()
            
            // In a real implementation, this would broadcast or update UI
            android.util.Log.d("ThermalMonitorWorker", "Temperature: ${temp}°C")
            
            return true
        } catch (e: Exception) {
            android.util.Log.e("ThermalMonitorWorker", "Monitoring failed: ${e.message}")
            return false // Continue monitoring on next cycle
        }
    }
}
