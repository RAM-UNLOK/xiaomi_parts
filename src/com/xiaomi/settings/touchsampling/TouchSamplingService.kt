/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-FileCopyrightText: 2025-2026 Rodin Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touchsampling

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vendor.xiaomi.hw.touchfeature.ITouchFeature

/** Maintains the selected touch sampling rate across screen-on events. */
class TouchSamplingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var touchFeature: ITouchFeature? = null
    private var screenUnlockReceiver: BroadcastReceiver? = null
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TouchSamplingService started")
        serviceScope.launch {
            initTouchFeature()
            applyTouchSamplingRateFromPreferences()
        }
        registerScreenUnlockReceiver()
        registerPreferenceChangeListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_TRANSIENT_PROFILE) {
            val profile = intent.getIntExtra(EXTRA_PROFILE, PROFILE_DEFAULT)
            serviceScope.launch {
                applyTouchSamplingRate(profile)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        runCatching { if (screenUnlockReceiver != null) unregisterReceiver(screenUnlockReceiver) }
        runCatching {
            getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    /** Non-blocking binding to the Xiaomi TouchFeature AIDL service. */
    private fun initTouchFeature() {
        runCatching {
            val fqName = ITouchFeature.DESCRIPTOR + "/default"
            val binder = android.os.ServiceManager.getService(fqName)
            if (binder != null) {
                touchFeature = ITouchFeature.Stub.asInterface(android.os.Binder.allowBlocking(binder))
                Log.d(TAG, "TouchFeature AIDL connected successfully")
            } else {
                Log.w(TAG, "TouchFeature AIDL service not yet available")
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to bind TouchFeature AIDL: $e")
        }
    }

    private fun registerScreenUnlockReceiver() {
        screenUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT ||
                    intent.action == Intent.ACTION_SCREEN_ON) {
                    serviceScope.launch {
                        applyTouchSamplingRateFromPreferences()
                    }
                }
            }
        }
        // ACTION_SCREEN_ON and ACTION_USER_PRESENT are system-protected broadcasts.
        // Do NOT pass RECEIVER_NOT_EXPORTED — the OS delivers these to all
        // registered receivers and the flag can silently prevent receipt on
        // some kernels.
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(
            screenUnlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    private fun registerPreferenceChangeListener() {
        val sharedPref = getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
        preferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == TOUCH_SAMPLING_PROFILE || key == HTSR_STATE) {
                    val profile = prefs.getInt(TOUCH_SAMPLING_PROFILE, if (prefs.getBoolean(HTSR_STATE, false)) 1 else 0)
                    serviceScope.launch {
                        applyTouchSamplingRate(profile)
                    }
                }
            }
        sharedPref.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun applyTouchSamplingRateFromPreferences() {
        val prefs = getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
        val profile = prefs.getInt(TOUCH_SAMPLING_PROFILE, if (prefs.getBoolean(HTSR_STATE, false)) 1 else 0)
        applyTouchSamplingRate(profile)
    }

    private fun applyTouchSamplingRate(profile: Int) {
        val tf = touchFeature ?: run {
            initTouchFeature()
            touchFeature
        }

        if (tf != null) {
            val sequence = PROFILE_SEQUENCES[profile] ?: PROFILE_SEQUENCES[PROFILE_DEFAULT]!!
            for ((mode, value) in sequence) {
                runCatching {
                    tf.setModeValue(0, mode, value)
                }.onFailure { e ->
                    Log.w(TAG, "setModeValue(0, $mode, $value) failed: $e")
                }
            }
            Log.d(TAG, "Applied touch profile $profile via TouchFeature HAL")
        }

        // Goodix hardware report-rate switch if accessible
        runCatching {
            val goodixNode = java.io.File("/sys/devices/platform/goodix_ts.0/switch_report_rate")
            if (goodixNode.exists()) {
                val rateValue = if (profile == PROFILE_240HZ || profile == PROFILE_DEFAULT) "0" else "1"
                goodixNode.writeText(rateValue)
                Log.d(TAG, "Goodix switch_report_rate set to $rateValue")
            }
        }

        // Also synchronize with local hardware daemon for 1000Hz resampling & memory patch
        syncWithDaemon(profile)
    }

    private fun syncWithDaemon(profile: Int) {
        val daemonCmd = "SET touch $profile"
        val socketCandidates = listOf("rodin_parts_daemon15", "rodin_essentiald_v15")
        var success = false

        for (sockName in socketCandidates) {
            runCatching {
                val socket = android.net.LocalSocket()
                socket.connect(android.net.LocalSocketAddress(sockName, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 1000
                socket.outputStream.write("$daemonCmd\n".toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                val buf = ByteArray(128)
                val read = socket.inputStream.read(buf)
                socket.close()
                val reply = if (read > 0) String(buf, 0, read).trim() else ""
                Log.d(TAG, "Hardware daemon ($sockName) reply for '$daemonCmd': $reply")
                success = true
            }
            if (success) break
        }

        if (!success) {
            Log.d(TAG, "Hardware daemon socket offline or unavailable for all candidates")
        }
    }

    companion object {
        private const val TAG = "TouchSamplingService"
        const val SHAREDHTSR = "htsr_prefs"
        const val HTSR_STATE = "htsr_enable"
        const val TOUCH_SAMPLING_PROFILE = "touch_sampling_profile"

        const val ACTION_SET_TRANSIENT_PROFILE = "com.xiaomi.settings.action.SET_TRANSIENT_TOUCH_PROFILE"
        const val EXTRA_PROFILE = "profile"

        const val PROFILE_DEFAULT = 0
        const val PROFILE_240HZ   = 1
        const val PROFILE_480HZ   = 2
        const val PROFILE_1000HZ  = 3
        const val PROFILE_380HZ   = 4

        fun applyTransientProfile(context: Context, profile: Int) {
            val intent = Intent(context, TouchSamplingService::class.java).apply {
                action = ACTION_SET_TRANSIENT_PROFILE
                putExtra(EXTRA_PROFILE, profile)
            }
            context.startService(intent)
        }

        private val PROFILE_SEQUENCES: Map<Int, List<Pair<Int, Int>>> = mapOf(
            PROFILE_DEFAULT to listOf(
                Pair(10001, 0), Pair(10002, 0), Pair(10003, 0), Pair(10004, 0),
                Pair(202, 0), Pair(0, 0), Pair(1, 0), Pair(2, 3), Pair(3, 2),
                Pair(4, 2), Pair(5, 2), Pair(6, 2), Pair(7, 0), Pair(1011, 240),
            ),
            PROFILE_240HZ to listOf(
                Pair(10001, 0), Pair(10002, 0), Pair(10003, 0), Pair(10004, 0),
                Pair(202, 0), Pair(0, 0), Pair(1, 0), Pair(2, 3), Pair(3, 2),
                Pair(4, 2), Pair(5, 2), Pair(6, 2), Pair(7, 0), Pair(1011, 240),
            ),
            PROFILE_380HZ to listOf(
                Pair(10001, 0), Pair(10002, 0), Pair(10003, 0), Pair(10004, 0),
                Pair(0, 1), Pair(1, 1), Pair(2, 99), Pair(3, 4), Pair(4, 4),
                Pair(5, 4), Pair(6, 0), Pair(7, 0), Pair(202, 1), Pair(1011, 380),
            ),
            PROFILE_480HZ to listOf(
                Pair(0, 1), Pair(1, 1), Pair(2, 4), Pair(3, 4), Pair(4, 4),
                Pair(5, 4), Pair(6, 0), Pair(7, 0), Pair(10002, 1), Pair(10003, 1),
                Pair(10004, 2), Pair(202, 1), Pair(10001, 1), Pair(1011, 480),
            ),
            PROFILE_1000HZ to listOf(
                Pair(0, 1), Pair(1, 1), Pair(2, 4), Pair(3, 4), Pair(4, 4),
                Pair(5, 4), Pair(6, 0), Pair(7, 0), Pair(10002, 1), Pair(10003, 1),
                Pair(10004, 2), Pair(202, 1), Pair(10001, 1), Pair(1011, 1000),
            ),
        )

        fun getProfile(context: Context): Int {
            val prefs = context.getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
            val stored = prefs.getInt(TOUCH_SAMPLING_PROFILE, -1)
            return if (stored == -1 || stored == PROFILE_DEFAULT) {
                PROFILE_240HZ
            } else {
                stored
            }
        }

        fun setProfile(context: Context, profile: Int) {
            context.getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
                .edit()
                .putInt(TOUCH_SAMPLING_PROFILE, profile)
                .putBoolean(HTSR_STATE, profile != PROFILE_240HZ)
                .apply()
            applyTransientProfile(context, profile)
        }
    }
}
