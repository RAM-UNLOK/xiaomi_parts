/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touchsampling

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Multi-sampling touch boost - allows different touch sampling modes based on context.
 * When combined with AutoTouchBoostService, provides adaptive input latency optimization.
 */
class MultiSamplingTouchBoost private constructor(private val context: Context) {

    companion object {
        const val SHARED_MULTI = "multi_sampling_prefs"
        const val PREFS_FILE = "touch_multi_sampling"

        /** Returns whether multi-sampling mode is enabled globally. */
        fun isEnabled(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_ENABLED, false)

        /** Enables or disables the global multi-sampling mode. */
        fun setEnabled(context: Context, enabled: Boolean) {
            runCatching {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                if (enabled) {
                    prefs.edit().putBoolean(PREFS_ENABLED, true).apply()
                } else {
                    prefs.edit().remove(PREFS_ENABLED).apply()
                }
            }.onFailure { /* log error */ }
        }

        /** Returns the current multi-sampling mode. */
        fun getMode(context: Context): MultiSamplingMode =
            readMultiPrefs(context)[MODE_KEY] ?: MODE_DYNAMIC

        /** Sets the multi-sampling mode with a transient intent to apply immediately. */
        fun setMode(context: Context, mode: MultiSamplingMode) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            runCatching {
                val currentMultiPrefs = readMultiPrefs(context).toMutableMap()
                currentMultiPrefs[MODE_KEY] = mode.ordinal
                prefs.edit().putString(MODES_PREFS_KEY, currentMultiPrefs.toString()).apply()
                
                // Apply the new mode via TouchSamplingService
                val intent = Intent(context, TouchSamplingService::class.java).apply {
                    action = TouchSamplingService.ACTION_SET_TRANSIENT_PROFILE
                    putExtra(TouchSamplingService.EXTRA_PROFILE, mode.profile)
                }
                context.startService(intent)
            }.onFailure { /* log error */ }

        /** Returns all stored multi-sampling preferences. */
        fun getModes(context: Context): Map<String, MultiSamplingMode> =
            readMultiPrefs(context).filterKeys { it != MODES_PREFS_KEY }

        private fun readMultiPrefs(context: Context): Map<String, Any?> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return try {
                val json = prefs.getString(MODES_PREFS_KEY, null) ?: "{}"
                if (json == "{}") emptyMap()
                else parseMultiJson(json)
            } catch (e: Exception) {
                emptyMap()
            }
        }

        private fun parseMultiJson(json: String): Map<String, Any?> {
            return try {
                val entries = json.split("},{")
                entries.mapNotNull { entry ->
                    val parts = entry.split(": ")
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val valueStr = parts[1].trim().removeSurrounding("\"")
                        val parsedValue = try { valueStr.toInt() } catch { null } ?: null
                        if (!key.isNullOrEmpty()) {
                            key to (parsedValue == null ? "" : parsedValue.toString())
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }.toMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

        private val PREFS_ENABLED = "multi_enabled"
        private const val MODES_PREFS_KEY = "multi_modes"
        
        /** The mode to use when none is explicitly set. */
        const val MODE_DEFAULT = 0
        
        enum class MultiSamplingMode(val profile: Int, val labelRes: Int) {
            DYNAMIC(0, R.string.touch_sampling_dynamic),           // Auto-select based on app
            LOW_LATENCY_240(1, R.string.touch_sampling_low_latency),   // 240Hz for responsiveness
            HIGH_RATE_380(2, R.string.touch_sampling_high_rate),     // 380Hz for gaming
            ULTRA_LOW_480(3, R.string.touch_sampling_ultra_low),    // 480Hz extreme latency reduction
            MAX_RATE_1000(4, R.string.touch_sampling_max_rate);     // 1000Hz maximum responsiveness
            
            override fun toString(): String {
                return "profile=$profile"
            }
        }

        private const val MODE_KEY = "multi_sampling_mode"

        /** Helper to apply the correct profile via TouchSamplingService. */
        fun applyMode(context: Context, mode: MultiSamplingMode) {
            if (mode.profile == 0) return // DYNAMIC doesn't need explicit application
            
            runCatching {
                val intent = Intent(context, TouchSamplingService::class.java).apply {
                    action = TouchSamplingService.ACTION_SET_TRANSIENT_PROFILE
                    putExtra(TouchSamplingService.EXTRA_PROFILE, mode.profile)
                }
                context.startService(intent)
            }.onFailure { /* log error */ }
        }

        /** Get the profile integer for a given mode. */
        fun getProfileForMode(mode: MultiSamplingMode): Int = mode.profile
        
        /** Get the display label for a given mode (for UI). */
        fun getLabel(context: Context, mode: MultiSamplingMode?): String {
            return if (mode == null) {
                context.getString(R.string.touch_sampling_dynamic)
            } else {
                try {
                    val label = context.getString(mode.labelRes)
                    label.ifEmpty { "Mode $mode" }
                } catch (e: Exception) {
                    "Mode ${mode.ordinal}"
                }
            }
        }

        /** Returns whether any mode is set that requires active touch boosting. */
        fun needsBoost(context: Context): Boolean =
            getModes(context).values.any { it != MODE_DEFAULT }

        /** Broadcast that other services listen to for changes. */
        const val ACTION_MULTI_SAMPLING_CHANGE = "com.xiaomi.settings.TOUCH_MULTI_SAMPLING_CHANGE"
    }

    init {
        // Apply current state on initialization
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wasEnabled = prefs.getBoolean(PREFS_ENABLED, false)
        if (wasEnabled && !isEnabled(context)) {
            setEnabled(context, true)
        } else if (!wasEnabled && isEnabled(context)) {
            setEnabled(context, false)
        }
    }
}
