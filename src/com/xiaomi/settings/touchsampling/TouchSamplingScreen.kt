/*
 * SPDX-FileCopyrightText: 2025-2026 Rodin Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touchsampling

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomi.settings.R
import com.xiaomi.settings.ui.PartsPreferenceGroup
import com.xiaomi.settings.ui.PartsScaffold
import com.xiaomi.settings.ui.PartsSelectionPreference
import com.xiaomi.settings.utils.PartsToast

private data class TouchRateOption(
    val profile: Int,
    val titleRes: Int,
    val descRes: Int,
)

private val TOUCH_OPTIONS = listOf(
    TouchRateOption(
        profile  = TouchSamplingService.PROFILE_240HZ,
        titleRes = R.string.touch_sampling_240_title,
        descRes  = R.string.touch_sampling_240_desc,
    ),
    TouchRateOption(
        profile  = TouchSamplingService.PROFILE_480HZ,
        titleRes = R.string.touch_sampling_480_title,
        descRes  = R.string.touch_sampling_480_desc,
    ),
    TouchRateOption(
        profile  = TouchSamplingService.PROFILE_1000HZ,
        titleRes = R.string.touch_sampling_1000_title,
        descRes  = R.string.touch_sampling_1000_desc,
    ),
)

@Composable
fun TouchSamplingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedProfile by remember {
        mutableIntStateOf(TouchSamplingService.getProfile(context))
    }
    var pendingOption by remember { mutableStateOf<TouchRateOption?>(null) }

    fun selectProfile(option: TouchRateOption) {
        if (selectedProfile == option.profile) return
        runCatching {
            TouchSamplingService.setProfile(context, option.profile)
            selectedProfile = option.profile
            PartsToast.show(
                context,
                context.getString(
                    R.string.touch_sampling_toast,
                    context.getString(option.titleRes),
                ),
            )
        }.onFailure {
            PartsToast.show(context, R.string.touch_sampling_failed)
        }
    }

    pendingOption?.let { option ->
        AlertDialog(
            onDismissRequest = { pendingOption = null },
            title = {
                Text(stringResource(R.string.touch_sampling_confirm_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.touch_sampling_confirm_message,
                        stringResource(option.titleRes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOption = null
                        selectProfile(option)
                    },
                ) {
                    Text(stringResource(R.string.touch_sampling_confirm_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOption = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    PartsScaffold(
        title = stringResource(R.string.touch_sampling_title),
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "profiles") {
                PartsPreferenceGroup {
                    TOUCH_OPTIONS.forEach { option ->
                        PartsSelectionPreference(
                            title = stringResource(option.titleRes),
                            summary = stringResource(option.descRes),
                            selected = selectedProfile == option.profile,
                            onClick = {
                                if (selectedProfile != option.profile) {
                                    pendingOption = option
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
