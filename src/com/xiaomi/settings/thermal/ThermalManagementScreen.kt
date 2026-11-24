/*
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.xiaomi.settings.R
import com.xiaomi.settings.ui.PartsCategoryTitle
import com.xiaomi.settings.ui.PartsInfoCard
import com.xiaomi.settings.ui.PartsPageGutter
import com.xiaomi.settings.ui.PartsPreference
import com.xiaomi.settings.ui.PartsPreferenceGroup
import com.xiaomi.settings.ui.PartsScaffold
import com.xiaomi.settings.ui.PartsSwitchPreference
import com.xiaomi.settings.touchsampling.AutoTouchBoostService
import com.xiaomi.settings.touchsampling.MultiSamplingTouchBoost
import com.xiaomi.settings.touchsampling.TouchSamplingPerApp
import com.xiaomi.settings.utils.PartsToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledAppEntry(val packageName: String, val label: String)

private fun appDesc(profile: MultiSamplingTouchBoost.MultiSamplingMode): String {
    return when (profile) {
        MultiSamplingTouchBoost.MultiSamplingMode.DYNAMIC -> "Auto-selects optimal rate based on foreground app"
        MultiSamplingTouchBoost.MultiSamplingMode.LOW_LATENCY_240 -> "Balanced responsiveness for general use"
        MultiSamplingTouchBoost.MultiSamplingMode.HIGH_RATE_380 -> "Enhanced touch feedback for gaming"
        MultiSamplingTouchBoost.MultiSamplingMode.ULTRA_LOW_480 -> "Extreme latency reduction for competitive play"
        MultiSamplingTouchBoost.MultiSamplingMode.MAX_RATE_1000 -> "Maximum responsiveness, highest power usage"
    }
}

data class TouchMultiRateOption(
    val profile: Int,
    val titleRes: Int,
    val descRes: Int,
    val label: String,
)

private fun MultiSamplingTouchBoost.MultiSamplingMode.profileToMulti(): TouchMultiRateOption {
    return when (this) {
        MultiSamplingTouchBoost.MultiSamplingMode.DYNAMIC -> TouchMultiRateOption(0, R.string.touch_sampling_dynamic, R.string.touch_sampling_dynamic_desc, "Dynamic")
        MultiSamplingTouchBoost.MultiSamplingMode.LOW_LATENCY_240 -> TouchMultiRateOption(1, R.string.touch_sampling_low_latency, R.string.touch_sampling_low_latency_desc, "240 Hz — low latency")
        MultiSamplingTouchBoost.MultiSamplingMode.HIGH_RATE_380 -> TouchMultiRateOption(2, R.string.touch_sampling_high_rate, R.string.touch_sampling_high_rate_desc, "380 Hz — high rate")
        MultiSamplingTouchBoost.MultiSamplingMode.ULTRA_LOW_480 -> TouchMultiRateOption(3, R.string.touch_sampling_ultra_low, R.string.touch_sampling_ultra_low_desc, "480 Hz — ultra-low")
        MultiSamplingTouchBoost.MultiSamplingMode.MAX_RATE_1000 -> TouchMultiRateOption(4, R.string.touch_sampling_max_rate, R.string.touch_sampling_max_rate_desc, "1000 Hz — maximum")
    }
}



@Composable
private fun appIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
@ReadOnlyComposable
private fun groupedShape(index: Int, total: Int): Shape {
    val outer = RoundedCornerShape(20.dp)
    val inner = RoundedCornerShape(4.dp)
    return when {
        total == 1         -> outer
        index == 0         -> outer.copy(bottomStart = inner.bottomStart, bottomEnd = inner.bottomEnd)
        index == total - 1 -> outer.copy(topStart = inner.topStart, topEnd = inner.topEnd)
        else               -> inner
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalManagementScreen(onBack: () -> Unit) {
    val context      = LocalContext.current
    val thermalUtils = remember { ThermalUtils.getInstance(context) }
    val scope        = rememberCoroutineScope()

    var thermalEnabled     by remember { mutableStateOf(thermalUtils.enabled) }
    var throttlingDisabled by remember { mutableStateOf(thermalUtils.throttlingDisabled) }
    var appList            by remember { mutableStateOf<List<AppThermalEntry>>(emptyList()) }
    var showResetDialog    by remember { mutableStateOf(false) }
    var pendingApp         by remember { mutableStateOf<AppThermalEntry?>(null) }
    var showAddSheet       by remember { mutableStateOf(false) }
    var pendingNewApp      by remember { mutableStateOf<InstalledAppEntry?>(null) }

    val controlsEnabled = thermalEnabled && !throttlingDisabled
    val addSheetState   = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        appList = withContext(Dispatchers.IO) {
            runCatching { ThermalService.getAppList(context) }.getOrDefault(emptyList())
        }
    }

    val profiles = remember { ThermalService.profiles() }
    PartsScaffold(
        title = stringResource(R.string.thermal_title),
        onBack = onBack,
        floatingActionButton = {
            if (controlsEnabled) {
                FloatingActionButton(
                    onClick        = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.thermal_add_app))
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top    = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            item(key = "enable+info") {
                PartsPreferenceGroup {
                    PartsSwitchPreference(
                        title = stringResource(R.string.thermal_enable_title),
                        checked = thermalEnabled,
                        enabled = !throttlingDisabled,
                        onCheckedChange = { checked ->
                            thermalEnabled = checked
                            thermalUtils.enabled = checked
                        },
                    )
                }
                PartsInfoCard(body = stringResource(R.string.thermal_enable_summary))
            }

            item(key = "auto_touch_boost") {
                PartsPreferenceGroup(title = stringResource(R.string.performance_category)) {
                    PartsSwitchPreference(
                        title = stringResource(R.string.auto_touch_boost_title),
                        checked = AutoTouchBoostService.isEnabled(context),
                        enabled = controlsEnabled,
                        onCheckedChange = { checked ->
                            runCatching {
                                AutoTouchBoostService.setEnabled(context, checked)
                            }.onFailure { /* log error */ }
                        },
                    )
                }
            }

            item(key = "touch_sampling") {
                PartsPreferenceGroup(title = stringResource(R.string.performance_category)) {
                    val mode by remember { MultiSamplingTouchBoost.getMode(context) }
                    val label by remember(mode) { 
                        MultiSamplingTouchBoost.getLabel(context, mode)
                    }
                    
                    PartsPreference(
                        title = stringResource(R.string.touch_sampling_title),
                        summary = if (mode != MultiSamplingTouchBoost.MODE_DEFAULT) label else "Select touch sampling mode",
                        icon = Icons.Filled.Memory,
                        enabled = controlsEnabled,
                        onClick = { showSamplingModeDialog() },
                    )
                }
            }

            item(key = "reset-card") {
                PartsPreferenceGroup {
                    PartsPreference(
                        title = stringResource(R.string.thermal_reset_title),
                        summary = stringResource(R.string.thermal_reset_summary),
                        icon = Icons.Filled.RestartAlt,
                        enabled = controlsEnabled,
                        destructive = true,
                        onClick = { showResetDialog = true },
                    )
                }
            }

            item(key = "per-app-label") {
                PartsCategoryTitle(stringResource(R.string.thermal_per_app_label))
            }

            if (appList.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text     = stringResource(R.string.thermal_no_apps),
                        style    = MaterialTheme.typography.bodyLarge,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = appList,
                    key   = { _, app -> app.packageName },
                ) { index, app ->
                    val shape     = groupedShape(index, appList.size)
                    val bottomPad = if (index == appList.lastIndex) 0.dp else 2.dp
                    AppThermalCard(
                        entry    = app,
                        enabled  = controlsEnabled,
                        shape    = shape,
                        modifier = Modifier
                            .padding(horizontal = PartsPageGutter)
                            .padding(bottom = bottomPad),
                        onClick  = { if (controlsEnabled) pendingApp = app },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AppPickerSheet(
            sheetState        = addSheetState,
            alreadyOverridden = appList.map { it.packageName }.toSet(),
            onDismiss         = {
                scope.launch { addSheetState.hide() }.invokeOnCompletion { showAddSheet = false }
            },
            onAppSelected     = { picked ->
                scope.launch { addSheetState.hide() }.invokeOnCompletion {
                    showAddSheet  = false
                    pendingNewApp = picked
                }
            },
        )
    }

        pendingProfileOption?.let { option ->
        AlertDialog(
            onDismissRequest = { pendingProfileOption = null },
            title = {
                Text(
                    text = stringResource(R.string.touch_sampling_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    text = stringResource(option.titleRes) + "\n\n" + option.descRes,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        MultiSamplingTouchBoost.setMode(context, option.profileToMulti())
                        pendingProfileOption = null
                    }
                ) {
                    Text(stringResource(R.string.touch_sampling_confirm_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProfileOption = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = {
                Icon(imageVector = Icons.Filled.RestartAlt, contentDescription = null)
            },
            title = {
                Text(
                    text  = stringResource(R.string.thermal_reset_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    text  = stringResource(R.string.thermal_reset_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            ThermalUtils.getInstance(context).resetProfiles()
                            appList = emptyList()
                        }
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.thermal_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    /**
     * Touch sampling mode picker dialog.
     */
    private fun showSamplingModeDialog() {
        val profiles = MultiSamplingTouchBoost.MultiSamplingMode.entries.map {
            MultiSamplingTouchRateOption(
                profile  = it.profile,
                titleRes = it.labelRes,
                descRes  = getDescFor(it),
                label    = it.toString(),
            )
        }

        pendingProfileOption = profiles.first() // Select default initially
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    sheetState:        androidx.compose.material3.SheetState,
    alreadyOverridden: Set<String>,
    onDismiss:         () -> Unit,
    onAppSelected:     (InstalledAppEntry) -> Unit,
) {
    val context       = LocalContext.current
    var query         by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            val pm    = context.packageManager
            val flags = PackageManager.GET_META_DATA
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, flags)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it !in alreadyOverridden }
                .mapNotNull { pkg ->
                    runCatching {
                        val info = pm.getApplicationInfo(pkg, 0)
                        InstalledAppEntry(pkg, pm.getApplicationLabel(info).toString())
                    }.getOrNull()
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    val filtered = remember(query, installedApps) {
        if (query.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text     = stringResource(R.string.thermal_add_app),
                style    = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            OutlinedTextField(
                value         = query,
                onValueChange = { query = it },
                placeholder   = { Text(stringResource(R.string.thermal_search_apps)) },
                leadingIcon   = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine    = true,
                shape         = MaterialTheme.shapes.extraLarge,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier       = Modifier.heightIn(max = 480.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val icon = appIcon(app.packageName)
                    ListItem(
                        modifier = Modifier.clickable { onAppSelected(app) },
                        leadingContent = {
                            if (icon != null) {
                                Image(
                                    bitmap             = icon,
                                    contentDescription = null,
                                    modifier           = Modifier.size(40.dp).clip(CircleShape),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector        = Icons.Filled.Apps,
                                        contentDescription = null,
                                        modifier           = Modifier.size(24.dp),
                                    )
                                }
                            }
                        },
                        headlineContent = {
                            Text(
                                text     = app.label,
                                style    = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text     = app.packageName,
                                style    = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppThermalCard(
    entry:    AppThermalEntry,
    enabled:  Boolean,
    shape:    Shape,
    modifier: Modifier = Modifier,
    onClick:  () -> Unit,
) {
    val context   = LocalContext.current
    val icon      = appIcon(entry.packageName)
    val isDefault = entry.profileId == ThermalUtils.ThermalState.DEFAULT.id

    Card(
        onClick  = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f),
        shape    = shape,
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        enabled  = enabled,
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier              = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (icon != null) {
                    Image(
                        bitmap             = icon,
                        contentDescription = null,
                        modifier           = Modifier.size(40.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Apps,
                            contentDescription = null,
                            modifier           = Modifier.size(24.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text     = entry.label,
                        style    = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text     = entry.packageName,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            VerticalDivider(
                modifier = Modifier.height(32.dp),
                color    = MaterialTheme.colorScheme.outlineVariant,
            )
            Box(
                modifier         = Modifier.width(100.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text     = ThermalService.profileLabel(context, entry.profileId),
                    style    = MaterialTheme.typography.labelLarge,
                    color    = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant
                               else          MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProfilePickerDialog(
    title:      String,
    profiles:   List<ThermalUtils.ThermalState>,
    selectedId: Int,
    onSelect:   (Int) -> Unit,
    onDismiss:  () -> Unit,
    onConfirm:  () -> Unit,
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape  = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                Text(
                    text     = title,
                    style    = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 336.dp),
                ) {
                    items(profiles) { profile ->
                        val selected = profile.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) { onSelect(profile.id) }
                                .padding(vertical = 14.dp, horizontal = 24.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                text  = context.getString(profile.label),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onConfirm) { Text(stringResource(R.string.thermal_apply)) }
                }
            }
        }
    }
}