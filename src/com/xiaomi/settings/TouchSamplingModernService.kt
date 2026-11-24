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
import com.xiaomi.settings.touchsampling.TouchSamplingService as LegacyTouchSamplingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * Phase 2B: Modern Touch Sampling Service using WorkManager + Scoped Foreground.
 * 
 * Key improvements over legacy implementation:
 * - Uses WorkManager for periodic state sync instead of BroadcastReceivers
 * - Structured concurrency with coroutine scope
 * - Proper foreground service type declaration
 */

class TouchSamplingModernService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "touch_sampling_channel"
        private const val NOTIFICATION_ID = 1002
    }
    
    private lateinit var legacyService: LegacyTouchSamplingService
    private var workRequest: WorkManager.WorkData? = null
    
    // Coroutine scope for structured concurrency
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    override fun onCreate() {
        super.onCreate()
        
        legacyService = LegacyTouchSamplingService(applicationContext)
        
        // Create notification channel (required for foreground service)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundInfo = ForegroundInfo(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Xiaomi Parts - Touch Sampling")
                .setSmallIcon(android.R.drawable.ic_menu_computer)
                .build(),
            START_TYPE_ADAPTABLE // Let system adjust priority based on battery
        )
        
        startForeground(NOTIFICATION_ID, foregroundInfo)
        
        // Enqueue periodic state sync work
        enqueuePeriodicSyncWork()
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        serviceScope.cancelAndJoin()
        
        if (workRequest != null) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("touch_sync_work")
        }
    }
    
    /**
     * Periodic state sync using WorkManager.
     */
    private fun enqueuePeriodicSyncWork() {
        val workRequest = PeriodicWorkRequestBuilder<TouchSyncWorker>(500) // Every 500ms
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10_000, // Max backoff: 10 seconds
                Constraints.Builder()
                    .setRequiresBatteryLevelAbove(20)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "touch_sync_work",
            ExistingPeriodicWorkReplacePolicy.KEEP,
            workRequest
        )
    }
    
    /**
     * Enable/disable HTSR boost mode.
     */
    fun setBoostMode(enabled: Boolean) {
        serviceScope.launch {
            try {
                if (enabled) {
                    legacyService.applyTouchSamplingRate(1) // HIGH_RATE mode
                } else {
                    legacyService.applyTouchSamplingRate(0) // GAME_MODE
                }
            } catch (e: Exception) {
                android.util.Log.e("TouchModern", "Failed to set boost mode: ${e.message}")
            }
        }
    }
    
    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(CHANNEL_ID, "Touch Sampling", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Manages high touch sampling rate (HTSR)"
        
        notificationManager.createNotificationChannel(channel)
    }
}

/**
 * Worker for periodic touch state sync.
 */
class TouchSyncWorker(
    context: Context,
    params: WorkManager.WorkData
) : PeriodicWorkProcessor(context, params) {
    
    override suspend fun process(): Boolean {
        try {
            // In a real implementation, this would sync with the AIDL service
            // For now, just check legacy service state
            
            val isBoost = LegacyTouchSamplingService.isBoostEnabled(applicationContext)
            
            android.util.Log.d("TouchSyncWorker", "HTSR Boost: $isBoost")
            
            return true
        } catch (e: Exception) {
            android.util.Log.e("TouchSyncWorker", "Sync failed: ${e.message}")
            return false
        }
    }
}
