/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.theme.SettingsTheme

/** Uses the same dynamic-colour and typography pipeline as Lineage Settings, fully M3-compliant for Android 17. */
@Composable
fun XiaomiPartsTheme(content: @Composable () -> Unit) {
    // SettingsTheme handles dynamic colors per device (Android 17+) with proper Material 3 theming
    SettingsTheme(content)
}

/** 
 * Android motion tokens shared by navigation and state changes.
 * Aligned with Material 3 animation guidelines for Android 17.
 */
object Motion {
    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Navigation spatial animation - matches M3 navigation behavior */
    fun <T> navSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.86f,
        stiffness = 420f,
    )

    /** Navigation effects animation with emphasis decelerate easing */
    fun <T> navEffectsSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = 220, easing = EmphasizedDecelerate)

    /** Default spring-based effect for general UI interactions */
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Press interaction animation - subtle scale feedback */
    fun <T> pressSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Live value animation for real-time data updates */
    fun <T> liveValueSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = 700, easing = StandardEasing)

    /** Shimmer loading effect - matches Material 3 loading patterns */
    fun <T> shimmerSpec(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = 2_800, easing = LinearEasing)

}

/** 
 * Shimmer alpha used for subtle shimmer effects.
 * Aligned with Material 3's subtle motion guidelines (Android 17).
 */
const val shimmerAlpha: Float = 0.05f
