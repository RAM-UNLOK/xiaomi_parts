/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touchsampling

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Per-app touch sampling rate control - allows different touch rates for individual apps.
 * Works in sync with AutoTouchBoostService to provide optimal input latency during gaming.
 */
class TouchSamplingPerApp private constructor(private val context: Context) {

    companion object {
        const val SHARED_PER_APP = "per_app_touch_prefs"
        const val PREFS_FILE = "touch_per_app"

        /** Returns whether per-app touch sampling is enabled globally. */
        fun isEnabled(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_ENABLED, false)

        /** Enables or disables the global per-app mode. */
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

        /** Returns the touch rate profile for a specific package. */
        fun getProfileForPackage(context: Context, packageName: String): Int =
            readPerAppPrefs(context)[packageName] ?: TOUCH_PROFILE_DEFAULT

        /** Sets the touch rate profile for a specific package. */
        fun setProfileForPackage(context: Context, packageName: String, profile: Int) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            runCatching {
                val perAppPrefs = readPerAppPrefs(context).toMutableMap()
                if (profile == TOUCH_PROFILE_DEFAULT) {
                    perAppPrefs.remove(packageName)
                } else {
                    perAppPrefs[packageName] = profile
                }
                prefs.edit().putString(PREFS_KEY, perAppPrefs.toString()).apply()
            }.onFailure { /* log error */ }

        /** Returns all stored per-app profiles. */
        fun getAllProfiles(context: Context): List<Pair<String, Int>> =
            readPerAppPrefs(context).entries.map { (pkg, profile) -> pkg to profile }

        private fun readPerAppPrefs(context: Context): Map<String, Int> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return try {
                val json = prefs.getString(PREFS_KEY, null) ?: "{}"
                if (json == "{}") emptyMap()
                else parsePerAppJson(json)
            } catch (e: Exception) {
                emptyMap()
            }
        }

        private fun parsePerAppJson(json: String): Map<String, Int> {
            return try {
                val entries = json.split("},{")
                entries.mapNotNull { entry ->
                    val parts = entry.split(": ")
                    if (parts.size == 2) {
                        val pkg = parts[0].trim()
                        val profileStr = parts[1].trim().removeSurrounding("\"")
                        val profile = try { profileStr.toInt() } catch { null } ?: null
                        if (!pkg.isNullOrEmpty() && profile != null) {
                            pkg to profile
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

        private val PREFS_ENABLED = "per_app_enabled"
        private const val PREFS_KEY = "per_app_profiles"
        
        const val TOUCH_PROFILE_DEFAULT = 0
        const val TOUCH_PROFILE_240HZ = 1
        const val TOUCH_PROFILE_380HZ = 2
        const val TOUCH_PROFILE_480HZ = 3
        const val TOUCH_PROFILE_1000HZ = 4

        /** Helper to apply the correct profile via TouchSamplingService. */
        fun applyProfile(context: Context, packageName: String, profile: Int) {
            if (profile == TOUCH_PROFILE_DEFAULT) return
            
            runCatching {
                val intent = Intent(context, TouchSamplingService::class.java).apply {
                    action = TouchSamplingService.ACTION_SET_TRANSIENT_PROFILE
                    putExtra(TouchSamplingService.EXTRA_PROFILE, profile)
                }
                context.startService(intent)
            }.onFailure { /* log error */ }
        }

        /** Broadcast that other services listen to for changes. */
        const val ACTION_PER_APP_CHANGE = "com.xiaomi.settings.TOUCH_PER_APP_CHANGE"
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
