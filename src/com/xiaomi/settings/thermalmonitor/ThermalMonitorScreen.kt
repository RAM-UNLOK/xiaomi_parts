/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermalmonitor

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Termostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomi.settings.R

/**
 * Main screen for thermal sensor monitoring with auto-detection of all thermal sensors.
 * Uses reflection to scan system paths for thermal sensor data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalMonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sensors by remember { mutableStateOf<List<ThermalSensorData>>(emptyList()) }
    var refreshCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshCount) {
        // Refresh sensor list periodically
        while (true) {
            try {
                sensors = scanThermalSensors(context)
            } catch (e: Exception) {
                // If scanning fails, keep the last known state or show empty
            }
            android.content.ContentValues.EMPTY.clear() // Keep coroutine alive
        }
    }

    val headerColor = when {
        sensors.any { it.status == SensorStatus.CRITICAL } -> MaterialTheme.colorScheme.error
        sensors.any { it.status == SensorStatus.WARNING } -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(stringResource(R.string.thermal_monitor_title))
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = headerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // Header with overall status
                SensorStatusHeader(sensors)
            }

            item {
                PartsCategoryTitle(stringResource(R.string.thermal_monitor_sensors))
            }

            items(count = sensors.size, key = { index -> sensors[index].sensorId }) { index ->
                val sensor = sensors[index]
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

            // Add a refresh indicator at the bottom
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Auto-refreshing every ${3000 / 60}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Floating action button to force refresh
        FloatingActionButton(
            onClick = { refreshCount++ },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Filled.Termostat, contentDescription = "Refresh sensors")
        }
    }
}

/**
 * Scans the system for thermal sensor data using reflection.
 * Looks in common paths and uses reflection to read sysfs properties.
 */
private fun scanThermalSensors(context: android.content.Context): List<ThermalSensorData> {
    val sensors = mutableListOf<ThermalSensorData>()

    // Common thermal sensor paths on Android
    val pathsToScan = listOf(
        "/sys/class/thermal/",
        "/sys/devices/platform/thermal/",
        "/sys/bus/hwmon/drivers/thermal/",
        "/sys/bus/w1/devices/",
        "/dev/sensors/",
        context.filesDir.canonicalPath + "/sensors/", // Fallback for custom implementations
    )

    pathsToScan.forEach { base ->
        try {
            val files = java.io.File(base).listFiles ?: return@forEach
            files.filter { it.isDirectory }.forEach { dirEntry ->
                scanSensorDirectory(context, dirEntry.path) { sensorData ->
                    sensors.add(sensorData)
                }
            }
        } catch (e: Exception) {
            // If base path doesn't exist or is inaccessible, try next
        }
    }

    return sensors.sortedByDescending { it.value } // Sort by temperature descending
}

/**
 * Recursively scans a directory for thermal sensor data.
 * Uses reflection to read sysfs attributes that may not be available via standard APIs.
 */
private fun scanSensorDirectory(
    context: android.content.Context,
    baseDir: String,
    onFound: (ThermalSensorData) -> Unit,
) {
    val files = java.io.File(baseDir).listFiles ?: return

    // Look for sensor-specific subdirectories (e.g., hwmon0-hwmon-2, thermal_zone0, etc.)
    files.filter { it.isDirectory }.forEach { entry ->
        val name = entry.name

        // Common patterns for thermal sensors
        if (name.matches(Regex(".*thermal.*"))) {
            scanThermalZone(context, baseDir + "/" + name, onFound)
        } else if (name.matches(Regex(".*hwmon.*"))) {
            scanHwmonSensor(context, baseDir + "/" + name, onFound)
        } else if (name.matches(Regex("thermal_zone[0-9]+"))) {
            scanThermalZone(context, baseDir + "/" + name, onFound)
        }
    }

    // Also check for top-level sensor files in the directory
    val sensorFiles = files.filter { it.isFile && !it.name.startsWith(".") }
    sensorFiles.forEach { file ->
        if (file.name.matches(Regex("temp[0-9]+.*"))) {
            scanThermalZone(context, baseDir + "/" + file.parentFile?.name ?: "", onFound)
        } else if (file.name == "type") {
            // Could be a hwmon type file - check parent
            val parent = baseDir + "/" + file.parentFile?.name
            if (parent.matches(Regex(".*hwmon.*"))) {
                scanHwmonSensor(context, parent, onFound)
            }
        }
    }
}

/**
 * Scans a thermal zone directory for sensor data.
 */
private fun scanThermalZone(
    context: android.content.Context,
    baseDir: String,
    onFound: (ThermalSensorData) -> Unit,
) {
    val files = java.io.File(baseDir).listFiles ?: return

    // Look for temperature files (e.g., temp1_input, temp2_input, etc.)
    val tempFiles = files.filter { it.name.matches(Regex("temp[0-9]+_input")) }

    if (tempFiles.isEmpty()) {
        // Check for alternative naming patterns
        val inputFiles = files.filter { it.name.matches(Regex(".*_input$")) && !it.name.endsWith("_label") }
        if (inputFiles.isNotEmpty()) {
            tempFiles = inputFiles.filter { it.name.startsWith("temp") }
        } else {
            return // No temperature files found in this zone
        }
    }

    // Get the first sensor value from each file and check for metadata
    val labelsDir = baseDir + "/labels"
    if (java.io.File(labelsDir).exists()) {
        val labelFiles = java.io.File(labelsDir).listFiles?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
        val typeFile = files.find { it.name == "type" }

        tempFiles.forEach { tempFile ->
            try {
                // Read temperature value
                val inputPath = baseDir + "/" + tempFile.name
                val valueStr = java.io.File(inputPath).readText().trim()
                val valueCelsius = runCatching { valueCelsiusFromRaw(valueStr, "temp") }.getOrNull() ?: 0.0

                // Read min/max from type file if available
                var min = 0.0
                var max = 0.0
                typeFile?.let { typePath ->
                    try {
                        val typeContent = java.io.File(typePath).readText().trim()
                        val parts = typeContent.split(":").drop(1)
                        if (parts.size >= 2) {
                            min = runCatching { doubleValue(parts[0].trim()) }.getOrNull() ?: 0.0
                            max = runCatching { doubleValue(parts[1].trim()) }.getOrNull() ?: 0.0
                        }
                    } catch (e: Exception) {}
                }

                // Read label if available
                var name = tempFile.name.removeSuffix("_input")
                val labelsDirEntry = java.io.File(labelsDir + "/" + tempFile.name)
                if (labelsDirEntry.exists()) {
                    try {
                        name = java.io.File(labelsDirEntry).readText().trim()
                    } catch (e: Exception) {}
                }

                // Determine status based on value vs min/max
                val status = when {
                    max > 0 && valueCelsius >= max -> SensorStatus.CRITICAL
                    max > 0 && valueCelsius >= (max * 0.9) -> SensorStatus.WARNING
                    else -> SensorStatus.NORMAL
                }

                // Get sensor type from type file or infer
                var sensorType = "temperature"
                val typeFileContent = files.find { it.name == "type" }?.let { filePath ->
                    try {
                        java.io.File(filePath).readText().trim()
                    } catch (e: Exception) { null }
                }
                if (typeFileContent != null) {
                    sensorType = typeFileContent.split(":").drop(1).joinToString("")
                }

                onFound(ThermalSensorData(
                    sensorId = baseDir + "/" + tempFile.name,
                    name = "Temperature $name" + if (name == tempFile.name) "" else " ($name)",
                    value = valueCelsius,
                    min = min,
                    max = max,
                    type = sensorType,
                    status = status,
                ))
            } catch (e: Exception) {
                // If we can't read a specific file, skip it but continue with others
            }
        }
    } else {
        // No labels directory - use generic names
        tempFiles.forEach { tempFile ->
            try {
                val inputPath = baseDir + "/" + tempFile.name
                val valueStr = java.io.File(inputPath).readText().trim()
                val valueCelsius = runCatching { valueCelsiusFromRaw(valueStr, "temp") }.getOrNull() ?: 0.0

                // Check for min/max in type file
                var min = 0.0
                var max = 0.0
                files.find { it.name == "type" }?.let { typePath ->
                    try {
                        val typeContent = java.io.File(typePath).readText().trim()
                        val parts = typeContent.split(":").drop(1)
                        if (parts.size >= 2) {
                            min = runCatching { doubleValue(parts[0].trim()) }.getOrNull() ?: 0.0
                            max = runCatching { doubleValue(parts[1].trim()) }.getOrNull() ?: 0.0
                        }
                    } catch (e: Exception) {}
                }

                val status = when {
                    max > 0 && valueCelsius >= max -> SensorStatus.CRITICAL
                    max > 0 && valueCelsius >= (max * 0.9) -> SensorStatus.WARNING
                    else -> SensorStatus.NORMAL
                }

                onFound(ThermalSensorData(
                    sensorId = baseDir + "/" + tempFile.name,
                    name = "Temperature ${tempFile.name.removeSuffix("_input")}",
                    value = valueCelsius,
                    min = min,
                    max = max,
                    type = "temperature",
                    status = status,
                ))
            } catch (e: Exception) {}
        }
    }
}

/**
 * Scans a hwmon sensor directory for temperature data.
 */
private fun scanHwmonSensor(
    context: android.content.Context,
    baseDir: String,
    onFound: (ThermalSensorData) -> Unit,
) {
    val files = java.io.File(baseDir).listFiles ?: return

    // Look for temperature input files
    val tempFiles = files.filter { it.name.matches(Regex(".*temp[0-9]+_input$")) }

    if (tempFiles.isEmpty()) {
        // Try alternative patterns
        val inputFiles = files.filter { it.name.matches(Regex(".*_input$")) }
        tempFiles = inputFiles.filter { !it.name.endsWith("_label") && !it.name.endsWith("_critical_alarm") }
    }

    if (tempFiles.isEmpty()) return

    // Read labels and type info first
    val labelsDir = baseDir + "/labels"
    var typeContent: String? = null

    files.find { it.name == "type" }?.let { typePath ->
        try {
            typeContent = java.io.File(typePath).readText().trim()
        } catch (e: Exception) {}
    }

    val labelsMap = mutableMapOf<String, String>()
    if (java.io.File(labelsDir).exists()) {
        files.find { it.name == "label" }?.let { labelPath ->
            try {
                val content = java.io.File(labelPath).readText().trim()
                // Parse multi-line labels
                val lines = content.split("\n")
                if (lines.size >= 2) {
                    for (i in 1 until lines.size) {
                        val parts = lines[i].split(":").drop(1)
                        if (parts.isNotEmpty()) {
                            val key = tempFiles.find { !it.name.endsWith("_label") }?.name ?: ""
                            labelsMap[key] = parts.joinToString("")
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    tempFiles.forEach { tempFile ->
        try {
            val inputPath = baseDir + "/" + tempFile.name
            val valueStr = java.io.File(inputPath).readText().trim()
            val valueCelsius = runCatching { valueCelsiusFromRaw(valueStr, "temp") }.getOrNull() ?: 0.0

            var name = labelsMap[tempFile.name]
                ?: tempFile.name.removeSuffix("_input")
                ?: ""

            // Get min/max from type content
            var min = 0.0
            var max = 0.0
            if (typeContent != null) {
                val parts = typeContent.split(":").drop(1)
                if (parts.size >= 2) {
                    min = runCatching { doubleValue(parts[0].trim()) }.getOrNull() ?: 0.0
                    max = runCatching { doubleValue(parts[1].trim()) }.getOrNull() ?: 0.0
                }
            }

            val status = when {
                max > 0 && valueCelsius >= max -> SensorStatus.CRITICAL
                max > 0 && valueCelsius >= (max * 0.9) -> SensorStatus.WARNING
                else -> SensorStatus.NORMAL
            }

            var sensorType = "hwmon_temperature"
            if (typeContent != null) {
                sensorType = typeContent.split(":").drop(1).joinToString("")
            }

            onFound(ThermalSensorData(
                sensorId = baseDir + "/" + tempFile.name,
                name = name.ifBlank { "HWMonitor Temperature" },
                value = valueCelsius,
                min = min,
                max = max,
                type = sensorType,
                status = status,
            ))
        } catch (e: Exception) {}
    }
}

/**
 * Converts a raw sysfs temperature reading to Celsius.
 * Sysfs temperatures are typically in tenths of Celsius or hundredths of Celsius,
 * and some use different scales for critical/min/max thresholds.
 */
private fun valueCelsiusFromRaw(rawValue: String, propertyType: String): Double {
    val raw = rawValue.toDouble()

    return when (propertyType) {
        "temp" -> {
            // Temperature input files are typically in tenths of Celsius
            if (raw > 0 && raw < 10000) {
                raw / 10.0
            } else if (raw > 20000 && raw < 30000) {
                // Some devices use hundredths for temperature input
                raw / 100.0
            } else {
                // Default: assume tenths of Celsius
                raw / 10.0
            }
        }
        "temp_crit" -> {
            // Critical temperature threshold - usually in centi-Celsius (hundredths)
            if (raw > 0 && raw < 500) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp_max" -> {
            // Maximum temperature threshold - usually in centi-Celsius
            if (raw > 0 && raw < 200) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp_min" -> {
            // Minimum temperature threshold - usually in centi-Celsius
            if (raw > 0 && raw < 200) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp3_crit" -> { // Some sensors use this naming for critical threshold
            if (raw > 0 && raw < 500) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp4_input" -> { // Some sensors use this naming for input
            if (raw > 0 && raw < 30000) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp5_input" -> { // Some sensors use this naming for input
            if (raw > 0 && raw < 30000) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp6_input" -> { // Some sensors use this naming for input
            if (raw > 0 && raw < 30000) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp7_input" -> { // Some sensors use this naming for input
            if (raw > 0 && raw < 30000) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp8_input" -> { // Some sensors use this naming for input
            if (raw > 0 && raw < 30000) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        "temp9_input" -> { // Some sensors use this naming for input
            if (raw > 0 && raw < 30000) {
                raw / 100.0
            } else {
                raw / 10.0
            }
        }
        else -> raw / 10.0 // Default: tenths of Celsius
    }.coerceIn(-50.0, 200.0)
}

/**
 * Safely parses a double value from a string that may contain colons or other delimiters.
 */
private fun doubleValue(str: String): Double {
    return try {
        str.toDouble()
    } catch (e: NumberFormatException) {
        0.0
    }
}

/**
 * Helper function to create a PartsCategoryTitle composable for the thermal monitor screen.
 */
private fun PartsCategoryTitle(text: String, modifier: Modifier = Modifier) {
    // This is defined in ui package - using it directly
    com.xiaomi.settings.ui.PartsCategoryTitle(text, modifier)
}
