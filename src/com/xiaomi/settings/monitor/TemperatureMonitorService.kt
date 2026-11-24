/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.settings.monitor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import android.os.SystemClock
import android.os.Temperature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vendor.xiaomi.hw.touchfeature.ITouchFeature

/**
 * Background service that periodically reads thermal data and battery temperature.
 * Handles lifecycle management and preference synchronization.
 */
class TemperatureMonitorService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var temperatureRepository: TemperatureMonitorRepository? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            temperatureRepository = TemperatureMonitorRepository(applicationContext)
            applyTemperatureMonitoringFromPreferences()
        }
        registerBroadcastReceiver()
        registerPreferenceChangeListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service is restarted if killed; resume monitoring on next onCreate
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        runCatching { if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver) }
        runCatching {
            applicationContext.getSharedPreferences(SHARED_TEMPERATURE, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    /**
     * Periodically reads thermal data and updates UI.
     * Runs on a background thread, safe to call from any context.
     */
    fun refresh() {
        val repository = temperatureRepository ?: return
        val snapshot = repository.read()
        val sampledTime = snapshot.sampledAtElapsed

        // Persist latest temperature readings for UI use
        persistSnapshot(snapshot)

        // Log relevant thermal events
        if (snapshot.thermalStatus != null && snapshot.thermalStatus >= Temperature.THROTTLING_LIGHT) {
            val severity = when (snapshot.thermalStatus) {
                Temperature.THROTTLING_LIGHT -> "light"
                Temperature.THROTTLING_MODERATE -> "moderate"
                Temperature.THROTTLING_SEVERE -> "severe"
                Temperature.THROTTLING_CRITICAL -> "critical"
                Temperature.THROTTLING_EMERGENCY -> "emergency"
                Temperature.THROTTLING_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
            android.util.Log.d(TAG, "Thermal throttling detected: $severity")
        }
    }

    /**
     * Applies the temperature monitoring rate from preferences.
     * Defaults to 2000ms refresh interval.
     */
    private fun applyTemperatureMonitoringFromPreferences() {
        val prefs = applicationContext.getSharedPreferences(SHARED_TEMPERATURE, Context.MODE_PRIVATE)
        val rateMs = prefs.getInt(TEMPERATURE_REFRESH_RATE, REFRESH_RATE_MS)
        val shouldMonitor = prefs.getBoolean(TEMPERATURE_ENABLED, true)

        if (shouldMonitor) {
            serviceScope.launch {
                var lastRefresh = SystemClock.elapsedRealtime()
                while (isServiceRunning) {
                    delay(REFRESH_RATE_MS.toLong())
                    if (isServiceRunning) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRefresh >= rateMs) {
                            lastRefresh = now
                            refresh()
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles battery temperature broadcasts and other system events.
     */
    private fun registerBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        // Battery temperature may have changed; refresh immediately
                        serviceScope.launch {
                            delay(50) // Small delay to ensure broadcast is processed
                            if (isServiceRunning) {
                                refresh()
                            }
                        }
                    }
                }
            }
        }
        // Battery broadcasts are system-protected; RECEIVER_NOT_EXPORTED may prevent receipt
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
            },
        )
    }

    /**
     * Registers for preference changes to dynamically adjust monitoring.
     */
    private fun registerPreferenceChangeListener() {
        val prefs = applicationContext.getSharedPreferences(SHARED_TEMPERATURE, Context.MODE_PRIVATE)
        preferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == TEMPERATURE_ENABLED || key == TEMPERATURE_REFRESH_RATE) {
                    serviceScope.launch {
                        applyTemperatureMonitoringFromPreferences()
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private var isServiceRunning = true
        set(value) {
            field = value
            if (!value) {
                // On service destruction, cancel ongoing monitoring
                serviceScope.cancel()
            }
        }

    /**
     * Persists the latest thermal snapshot to shared preferences.
     * This allows the UI to recover state after configuration changes.
     */
    private fun persistSnapshot(snapshot: TemperatureSnapshot) {
        val prefs = applicationContext.getSharedPreferences(SHARED_TEMPERATURE, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(TEMPERATURE_STATUS, snapshot.thermalStatus ?: 0)
        editor.putLong(TEMPERATURE_CPU_MAX, snapshot.cpuMaximumC ?: 0f).putFloat(TEMPERATURE_BATTERY, snapshot.batteryC ?: 0f)
        editor.putLong(TEMPERATURE_SAMPLED_AT, snapshot.sampledAtElapsed)
        // Sensors data can be serialized as JSON, but for simplicity we skip persistence here
        editor.apply()
    }

    companion object {
        private const val TAG = "TemperatureMonitorService"
        const val SHARED_TEMPERATURE = "temperature_monitor_prefs"
        const val TEMPERATURE_ENABLED = "temperature_enabled"
        const val TEMPERATURE_REFRESH_RATE = "temperature_refresh_rate"
        const val REFRESH_RATE_MS = 2000

        const val TEMPERATURE_STATUS = "temperature_status"
        const val TEMPERATURE_CPU_MAX = "temperature_cpu_max"
        const val TEMPERATURE_BATTERY = "temperature_battery"
        const val TEMPERATURE_SAMPLED_AT = "temperature_sampled_at"

        fun startMonitoring(context: Context) {
            val intent = Intent(context, TemperatureMonitorService::class.java)
            context.startService(intent)
        }

        fun stopMonitoring(context: Context) {
            val intent = Intent(context, TemperatureMonitorService::class.java)
            context.stopService(intent)
        }
    }
}