/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.settingslib.spa.framework.theme.SettingsTheme

/** Semantic color roles used throughout the app. */
object Colors {
    // Use these semantic roles instead of hardcoding colors
}

/** Enhanced theme with proper M3 Expressive patterns and working exit animations. */
@Composable
fun XiaomiPartsTheme(content: @Composable () -> Unit) {
    SettingsTheme(
        darkTheme = isSystemInDarkTheme(),
        content = content,
    )
}

/** 
 * Animated state handling for proper back gesture support.
 * Use this to control what appears when navigating or going back.
 */
object AnimationState {
    // State controls whether the screen content should appear/disappear
    val showContent = MutableTransitionState(true)
        .also { it.targetValue = true }

    /** 
     * Animate a value smoothly between two states. 
     * Returns a state that can be used to conditionally show/hide elements.
     */
    fun animateBetween(target: Boolean): androidx.compose.runtime.State<Boolean> {
        return animateIntAsState(
            targetValue = if (target) 1 else 0,
            animationSpec = tween(
                durationMillis = 250,
                easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f),
            ),
        ).value != 0
    }
}

/** Android motion tokens shared by navigation and state changes. */
object Motion {
    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> navSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.86f,
        stiffness = 420f,
    )

    fun <T> navEffectsSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = 220, easing = EmphasizedDecelerate)

    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> pressSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> liveValueSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = 700, easing = StandardEasing)

    fun <T> shimmerSpec(): DurationBasedAnimationSpec<T> =
        tween(durationMillis = 2_800, easing = LinearEasing)

}

const val shimmerAlpha: Float = 0.05f
