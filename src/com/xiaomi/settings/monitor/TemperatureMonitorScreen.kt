/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.settings.monitor

import android.os.Temperature
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cpu
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FlipperDogs
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomi.settings.R
import com.xiaomi.settings.ui.PartsCategoryTitle
import com.xiaomi.settings.ui.PartsGroupShape
import com.xiaomi.settings.ui.PartsPageGutter
import com.xiaomi.settings.ui.PartsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class HistorySample(val time: Long, val cpu: Float?, val battery: Float?)

@Composable
fun TemperatureMonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { TemperatureMonitorRepository(context) }
    var snapshot by remember { mutableStateOf(TemperatureSnapshot()) }
    var history by remember { mutableStateOf(emptyList<HistorySample>()) }
    var batteryC by remember { mutableFloatStateOf(0f) }
    var cpuC by remember { mutableFloatStateOf(0f) }
    var isThrottling by remember { mutableStateOf(false) }

    // Periodically refresh data on IO dispatcher
    LaunchedEffect(repository) {
        while (isActive) {
            val reading = withContext(Dispatchers.IO) { repository.read() }
            snapshot = reading
            batteryC = reading.batteryC ?: 0f
            cpuC = reading.cpuMaximumC ?: 0f
            isThrottling = reading.thermalStatus != null && reading.thermalStatus >= Temperature.THROTTLING_LIGHT
            history = (history + HistorySample(reading.sampledAtElapsed, reading.cpuMaximumC, reading.batteryC))
                .filter { it.time >= reading.sampledAtElapsed - 120_000 }
                .takeLast(60)
            delay(2_000)
        }
    }

    val thermalColor = if (isThrottling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    PartsScaffold(
        title = stringResource(R.string.temperature_monitor_title),
        onBack = onBack,
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // Status section
            item("status") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = PartsGroupShape,
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.temperature_monitor_status),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(severityLabel(snapshot.thermalStatus)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = thermalColor,
                                fontWeight = if (isThrottling) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        Text(
                            text = stringResource(R.string.temperature_monitor_sampled, snapshot.sampledAtElapsed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Live metrics
            item("live") {
                PartsCategoryTitle(stringResource(R.string.temperature_monitor_live))
            }

            // CPU/GPU temperatures
            item("cpu_gpu") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PartsPageGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.temperature_monitor_cpu_max),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = temperatureText(cpuC),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.temperature_monitor_gpu_max),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = temperatureText(snapshot.gpuMaximumC ?: 0f),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Battery & Skin temperatures
            item("battery_skin") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PartsPageGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.temperature_monitor_battery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = temperatureText(batteryC),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.temperature_monitor_skin_max),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = temperatureText(snapshot.skinMaximumC ?: 0f),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Sensor list
            item("sensors") {
                PartsCategoryTitle(stringResource(R.string.temperature_monitor_sensors))
            }

            if (snapshot.sensors.isEmpty()) {
                item("no_sensors") {
                    Text(
                        text = stringResource(R.string.temperature_monitor_no_sensors),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(snapshot.sensors) { sensor ->
                    SensorRow(sensor)
                }
            }

            // History chart
            item("history") {
                PartsCategoryTitle(stringResource(R.string.temperature_monitor_history))
                TemperatureHistory(history, batteryC, cpuC)
            }
        }
    }
}

@Composable
private fun SensorRow(sensor: TemperatureReading) {
    val severityColor = sensor.severity?.let { severity ->
        when (severity) {
            Temperature.THROTTLING_LIGHT -> MaterialTheme.colorScheme.tertiary
            Temperature.THROTTLING_MODERATE -> MaterialTheme.colorScheme.error
            else -> null
        }
    } ?: MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = when (sensor.type) {
                        Temperature.TYPE_CPU -> Icons.Filled.Cpu
                        Temperature.TYPE_GPU -> Icons.Filled.Memory
                        Temperature.TYPE_BATTERY -> Icons.Filled.BatteryChargingFull
                        Temperature.TYPE_SKIN -> Icons.Filled.Sensors
                        Temperature.TYPE_MODEM -> Icons.Filled.Devices
                        else -> Icons.Filled.FlippersDogs
                    },
                    contentDescription = null,
                    tint = severityColor,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(
                        text = sensor.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sensor.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (sensor.valueC != null) {
                Text(
                    text = temperatureText(sensor.valueC),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = severityColor,
                )
            } else {
                Text(
                    text = stringResource(R.string.temperature_monitor_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TemperatureHistory(
    history: List<HistorySample>,
    batteryC: Float,
    cpuC: Float,
) {
    val cpuColor = MaterialTheme.colorScheme.primary
    val batteryColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val values = history.flatMap { listOfNotNull(it.cpu, it.battery) }
    val observedMin = values.minOrNull()
    val observedMax = values.maxOrNull()
    val low = (observedMin ?: 30f) - 2f
    val high = (observedMax ?: 45f) + 2f
    val mid = (high + low) / 2f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "● " + stringResource(R.string.temperature_monitor_cpu_max),
                    color = cpuColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "● " + stringResource(R.string.temperature_monitor_battery),
                    color = batteryColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(116.dp),
                ) {
                    val strokeDashed = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                    for (fraction in listOf(0.08f, 0.5f, 0.92f)) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, size.height * fraction),
                            end = Offset(size.width, size.height * fraction),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }

                    if (history.size >= 2 && values.isNotEmpty()) {
                        val start = history.first().time
                        val span = (history.last().time - start).coerceAtLeast(1L)

                        fun plot(color: Color, read: (HistorySample) -> Float?) {
                            val path = Path()
                            var connected = false
                            var previousTime: Long? = null
                            history.forEach { sample ->
                                val value = read(sample)
                                if (value == null) {
                                    connected = false
                                } else {
                                    val x = size.width * ((sample.time - start).toFloat() / span)
                                    val y = size.height * (0.08f + 0.84f * (1f - (value - low) / (high - low).coerceAtLeast(1f)))
                                    if (connected && previousTime != null && sample.time - previousTime!! <= 5_000) {
                                        path.lineTo(x, y)
                                    } else {
                                        path.moveTo(x, y)
                                    }
                                    connected = true
                                    drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, y))
                                }
                                previousTime = sample.time
                            }
                            drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
                        }

                        plot(cpuColor) { it.cpu }
                        plot(batteryColor) { it.battery }
                    }
                }

                // Dynamic Temperature Scale Axis
                Column(
                    modifier = Modifier.height(116.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${high.roundToInt()} °C",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${mid.roundToInt()} °C",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${low.roundToInt()} °C",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = stringResource(R.string.temperature_monitor_history_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun temperatureText(value: Float): String = String.format(
    Locale.getDefault(),
    "%.1f °C",
    value,
)

private fun severityLabel(severity: Int?): Int = when (severity) {
    Temperature.THROTTLING_NONE -> R.string.temperature_monitor_severity_none
    Temperature.THROTTLING_LIGHT -> R.string.temperature_monitor_severity_light
    Temperature.THROTTLING_MODERATE -> R.string.temperature_monitor_severity_moderate
    Temperature.THROTTLING_SEVERE -> R.string.temperature_monitor_severity_severe
    Temperature.THROTTLING_CRITICAL -> R.string.temperature_monitor_severity_critical
    Temperature.THROTTLING_EMERGENCY -> R.string.temperature_monitor_severity_emergency
    Temperature.THROTTLING_SHUTDOWN -> R.string.temperature_monitor_severity_shutdown
    else -> R.string.temperature_monitor_severity_none
}