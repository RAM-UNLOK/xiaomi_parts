/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermalmonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class ThermalSensorData(
    val sensorId: String,
    val name: String,
    val value: Double, // in Celsius
    val min: Double,
    val max: Double,
    val type: String,
    val status: SensorStatus,
)

enum class SensorStatus {
    NORMAL, WARNING, CRITICAL
}

@Composable
fun ThermalSensorCard(
    sensorId: String,
    name: String,
    valueCelsius: Double,
    min: Double,
    max: Double,
    type: String,
    status: SensorStatus,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        SensorStatus.CRITICAL -> MaterialTheme.colorScheme.error
        SensorStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        SensorStatus.NORMAL -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Text(
                    text = String.format("%.2f °C", valueCelsius),
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = sensorId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Type: $type",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val statusText = when (status) {
                    SensorStatus.CRITICAL -> "CRITICAL"
                    SensorStatus.WARNING -> "WARNING"
                    SensorStatus.NORMAL -> "NORMAL"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )

                if (min != max) {
                    Text(
                        text = String.format("%.0f–%.0f °C", min, max),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ThermalSensorList(
    sensors: List<ThermalSensorData>,
    modifier: Modifier = Modifier,
) {
    if (sensors.isEmpty()) {
        Card(modifier = modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No thermal sensors found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column(modifier = modifier) {
            sensors.forEach { sensor ->
                ThermalSensorCard(
                    sensorId = sensor.sensorId,
                    name = sensor.name,
                    valueCelsius = sensor.value,
                    min = sensor.min,
                    max = sensor.max,
                    type = sensor.type,
                    status = sensor.status,
                )
            }
        }
    }
}
