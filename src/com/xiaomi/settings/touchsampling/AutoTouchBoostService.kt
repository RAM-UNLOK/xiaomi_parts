/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touchsampling

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.preference.PreferenceManager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

/**
 * Auto-touch boost service that automatically enables touch sampling rate boost
 * when gaming thermal profiles are active. This reduces input latency for competitive
 * and heavy games by raising the touch controller polling rate.
 */
class AutoTouchBoostService : Service() {

    private var isBoostEnabled = false
    private var lastAppPackage: String? = null
    private var notificationManager: NotificationManager? = null
    private var boostNotificationId = 0

    // Track whether other touch sampling modes are active (for coordination)
    private var perAppModeActive = false
    private var multiSamplingModeActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AutoTouchBoostService started")
        
        // Create notification channel for boost status
        val channelId = "auto_touch_boost"
        val channelName = "Auto Touch Boost"
        val descriptionText = "Shows when touch sampling rate boost is active during gaming"
        
        runCatching {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.description = descriptionText
            
            notificationManager?.let { manager ->
                manager.createNotificationChannel(channel)
            }
        }.onFailure { /* ignore */ }
        
        registerBroadcastReceiver()
        applyCurrentState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AutoTouchBoostService stopped")
        // Disable boost when service is destroyed
        runCatching { TouchSamplingService.setEnabled(this, false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBroadcastReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ThermalService.ACTION_THERMAL_PROFILE_CHANGED -> {
                        Log.d(TAG, "Thermal profile changed — checking for gaming profile")
                        handleProfileChange(intent.getStringExtra("package_name"))
                    },
                    TouchSamplingPerApp.ACTION_PER_APP_CHANGE -> {
                        Log.d(TAG, "Per-app mode changed")
                        perAppModeActive = true
                        // If per-app is active and we're not already boosted, enable boost
                        if (isBoostEnabled) {
                            Log.d(TAG, "Per-app active with boost — maintaining sync")
                        } else {
                            runCatching { TouchSamplingService.setEnabled(this, true) }
                            isBoostEnabled = true
                            showBoostNotification("PER_APP_MODE")
                        }
                    },
                    MultiSamplingTouchBoost.ACTION_MULTI_SAMPLING_CHANGE -> {
                        Log.d(TAG, "Multi-sampling mode changed")
                        multiSamplingModeActive = true
                        // If multi-sampling is active and we're not already boosted, enable boost
                        if (isBoostEnabled) {
                            Log.d(TAG, "Multi-sampling active with boost — maintaining sync")
                        } else {
                            runCatching { TouchSamplingService.setEnabled(this, true) }
                            isBoostEnabled = true
                            showBoostNotification("MULTI_SAMPLING_MODE")
                        }
                    },
                }
            }
        }

        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ThermalService.ACTION_THERMAL_PROFILE_CHANGED)
                addAction(TouchSamplingPerApp.ACTION_PER_APP_CHANGE)
                addAction(MultiSamplingTouchBoost.ACTION_MULTI_SAMPLING_CHANGE)
            },
        )
    }

    private fun handleProfileChange(packageName: String?) {
        // If a new package is reported and it's a gaming profile, enable touch boost
        if (packageName != null && !isBoostEnabled) {
            val isGamingProfile = packageName in GAMING_PROFILE_PKGS
            if (isGamingProfile) {
                Log.d(TAG, "Detected gaming app: $packageName — enabling touch boost")
                runCatching { TouchSamplingService.setEnabled(this, true) }
                isBoostEnabled = true
                lastAppPackage = packageName
                
                // Show notification when boost is enabled
                showBoostNotification(packageName)
            }
        }

        // If the current package leaves the gaming set and we're still active, disable boost
        if (lastAppPackage != null && !packageName.isNullOrEmpty()) {
            val wasGaming = lastAppPackage in GAMING_PROFILE_PKGS
            val isNowGaming = packageName in GAMING_PROFILE_PKGS
            if (!isNowGaming && wasGaming) {
                Log.d(TAG, "Left gaming profile — disabling touch boost")
                
                // Remove notification when boost is disabled
                removeBoostNotification()
                
                runCatching { TouchSamplingService.setEnabled(this, false) }
                isBoostEnabled = false
            }
        }
    }

    private fun showBoostNotification(packageName: String) {
        val intent = Intent(this, AutoTouchBoostService::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        boostNotificationId++
        
        val notification = NotificationCompat.Builder(this, "auto_touch_boost")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Touch Boost Active")
            .setContentText("$packageName — High polling rate enabled for optimal touch response")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "The touch sampling rate has been boosted to reduce input latency."
            ))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            notificationManager?.let { manager ->
                manager.notify(boostNotificationId, notification)
            }
        }.onFailure { /* ignore */ }
    }

    private fun removeBoostNotification() {
        if (boostNotificationId > 0) {
            runCatching {
                notificationManager?.let { manager ->
                    manager.cancel(boostNotificationId)
                }
            }.onFailure { /* ignore */ }
        }
    }

    private fun applyCurrentState() {
        // Apply the current state from preferences if already set
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val wasEnabled = prefs.getBoolean(AUTO_TOUCH_BOOST_ENABLED, false)
        if (wasEnabled && !isBoostEnabled) {
            runCatching { TouchSamplingService.setEnabled(this, true) }
            isBoostEnabled = true
        } else if (!wasEnabled && isBoostEnabled) {
            runCatching { TouchSamplingService.setEnabled(this, false) }
            isBoostEnabled = false
        }
    }

    companion object {
        private const val TAG = "AutoTouchBoost"

        /** SharedPreferences key for auto touch boost enabled state. */
        const val AUTO_TOUCH_BOOST_ENABLED = "auto_touch_boost_enabled"

        /** Package prefixes that are considered gaming apps for automatic touch boost. */
        private val GAMING_PROFILE_PKGS = listOf(
            // Competitive games (GAMING_COMPETITIVE)
            "com.tencent.ig",      // PUBG Mobile
            "com.vng.pubgmobile",  // PUBG Mobile VN
            "com.rekoo.pubgm",     // PUBG Mobile KR
            "com.dts.freefireth",  // Free Fire
            "com.dts.freefiremax",  // Free Fire MAX
            "com.mobile.legends",  // Mobile Legends
            "com.riotgames.league.wildrift", // Wild Rift
            "com.epicgames.fortnite", // Fortnite
            "com.tencent.tmgp.sgame", // Honor of Kings

            // Heavy games (GAMING_HEAVY)
            "com.tencent.tmgp.pubgmhd",  // PUBG Mobile HD
            "com.activision.callofduty", // Call of Duty Mobile
            "com.ea.games",             // EA Games
            "com.pubg.imobile",         // PUBG Mobile IME
            "com.LevelInfinite.Hotta.tof", // Tower of Fantasy

            // General gaming (GAMING)
            "com.miHoYo.Yuanshen",     // Genshin Impact
            "com.miHoYo.GenshinImpact",  // Genshin Impact CN
            "com.kurogame.wutheringwaves", // Wuthering Waves
            "com.kurogame.gplay.punishing.grayraven.en" // Punishing: Gray Raven
        )

        /** Returns whether auto touch boost is enabled. */
        fun isEnabled(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(AUTO_TOUCH_BOOST_ENABLED, false)

        /** Enables or disables the auto-touch-boost feature. */
        fun setEnabled(context: Context, enabled: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            runCatching {
                if (enabled) {
                    prefs.edit().putBoolean(AUTO_TOUCH_BOOST_ENABLED, true).apply()
                } else {
                    prefs.edit().remove(AUTO_TOUCH_BOOST_ENABLED).apply()
                }
                
                // If the service is running and state changed, notify it to update
                val intent = Intent(context, AutoTouchBoostService::class.java)
                context.sendBroadcast(intent)
            }.onFailure { /* log error */ }
        }
    }
}
