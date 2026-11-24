/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.settings.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IThermalService
import android.os.PowerManager
import android.os.ServiceManager
import android.os.SystemClock
import android.os.Temperature
import java.io.File

/**
 * Real-time temperature monitoring with battery temperature detection.
 * Combines thermal HAL readings with battery temperature from broadcasts.
 */
data class TemperatureReading(
    val id: String,
    val name: String,
    val type: Int,
    val valueC: Float?,
    val severity: Int?,
)

data class TemperatureSnapshot(
    val sensors: List<TemperatureReading> = emptyList(),
    val thermalStatus: Int? = null,
    val sampledAtElapsed: Long = 0,
) {
    val batteryC: Float? get() = maximum(Temperature.TYPE_BATTERY)
    val cpuMaximumC: Float? get() = maximum(Temperature.TYPE_CPU, preferPhysicalZones = true)
    val gpuMaximumC: Float? get() = maximum(Temperature.TYPE_GPU, preferPhysicalZones = true)
    val skinMaximumC: Float? get() = maximum(Temperature.TYPE_SKIN)

    private fun maximum(type: Int, preferPhysicalZones: Boolean = false): Float? {
        val matching = sensors.filter { it.type == type && it.valueC != null }
        // Prefer explicitly identified MT6899 CPU/GPU zones when available.
        val physical = if (preferPhysicalZones) matching.filter { it.id.startsWith("sysfs:") } else emptyList()
        return physical.ifEmpty { matching }.mapNotNull { it.valueC }.maxOrNull()
    }
}

/**
 * Reads thermal data from HAL and kernel zones, plus battery temperature.
 * Runs on a worker thread with periodic updates.
 */
class TemperatureMonitorRepository(context: Context) {
    private val context = context.applicationContext

    fun read(): TemperatureSnapshot {
        val sampledAtElapsed = SystemClock.elapsedRealtime()
        val service = runCatching {
            IThermalService.Stub.asInterface(ServiceManager.getService(Context.THERMAL_SERVICE))
        }.getOrNull()
        val framework = runCatching { service?.currentTemperatures?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { TemperatureValues.isCelsiusType(it.type) }
            .map { sensor ->
                TemperatureReading(
                    id = "hal:${sensor.type}:${sensor.name}",
                    name = sensor.name,
                    type = sensor.type,
                    valueC = TemperatureValues.celsius(sensor.value),
                    severity = sensor.status.takeIf(Temperature::isValidStatus),
                )
            }.distinctBy { it.id }
        val kernel = readKernelZones()
        val sensors = (framework + kernel).toMutableList()
        if (sensors.none { it.type == Temperature.TYPE_BATTERY && it.valueC != null }) {
            readBatteryTemperature()?.let { value ->
                sensors += TemperatureReading("battery:broadcast", "battery", Temperature.TYPE_BATTERY, value, null)
            }
        }
        val status = runCatching { service?.currentThermalStatus }.getOrNull()
            ?: runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()
        return TemperatureSnapshot(
            sensors = sensors.sortedWith(compareBy<TemperatureReading> { it.type }.thenBy { it.id }),
            thermalStatus = status?.takeIf(Temperature::isValidStatus),
            sampledAtElapsed = sampledAtElapsed,
        )
    }

    private fun readKernelZones(): List<TemperatureReading> {
        val root = File("/sys/class/thermal")
        val zones = runCatching { root.listFiles()?.filter { ZONE_NAME.matches(it.name) }.orEmpty() }
            .getOrDefault(emptyList())
        return zones.sortedBy { it.name }.take(128).mapNotNull { zone ->
            val name = readSmallFile(File(zone, "type"))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TemperatureReading(
                id = "sysfs:${zone.name}",
                name = name,
                type = TemperatureValues.typeForRodinZone(name),
                valueC = TemperatureValues.millidegrees(readSmallFile(File(zone, "temp"))),
                severity = null,
            )
        }
    }

    private fun readBatteryTemperature(): Float? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching null
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ||
            !intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) return@runCatching null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        TemperatureValues.celsius(tenths / 10f)
    }.getOrNull()

    private fun readSmallFile(file: File): String? = runCatching {
        file.bufferedReader().use { it.readLine()?.take(128)?.trim() }
    }.getOrNull()

    private companion object {
        val ZONE_NAME = Regex("thermal_zone[0-9]+")
    }
}

/**
 * Unit conversion and exact device-tree mappings.
 * Keeps host validation separate from device-specific logic.
 */
internal object TemperatureValues {
    fun celsius(value: Float): Float? = value.takeIf { it.isFinite() && it in -40f..150f }

    fun millidegrees(raw: String?): Float? = raw?.trim()?.toLongOrNull()
        ?.takeIf { it in -40_000L..150_000L }?.let { celsius(it / 1000f) }

    fun isCelsiusType(type: Int): Boolean = Temperature.isValidType(type) && type !in setOf(
        Temperature.TYPE_BCL_VOLTAGE, Temperature.TYPE_BCL_CURRENT, Temperature.TYPE_BCL_PERCENTAGE,
    )

    fun typeForRodinZone(name: String): Int = when {
        name in CPU_ZONES -> Temperature.TYPE_CPU
        name in setOf("gpu0", "gpu1", "gpu2") -> Temperature.TYPE_GPU
        name == "battery" -> Temperature.TYPE_BATTERY
        name == "ap_ntc" || name == "vtskin-max" || name in SKIN_ZONES -> Temperature.TYPE_SKIN
        name in SOC_ZONES -> Temperature.TYPE_SOC
        name in setOf("md1", "md2", "md3", "md4") -> Temperature.TYPE_MODEM
        name in setOf("nrpa_ntc", "ltepa_ntc") -> Temperature.TYPE_POWER_AMPLIFIER
        name == "mtk-master-charger" -> Temperature.TYPE_USB_PORT
        else -> Temperature.TYPE_UNKNOWN
    }

    // Exact zone names from mt6899.dts; unknown names remain unclassified.
    private val CPU_ZONES = setOf(
        "cpu-little-core0", "cpu-little-core1", "cpu-little-core2", "cpu-little-core3",
        "cpu-medium-core4-0", "cpu-medium-core4-1", "cpu-medium-core5-0", "cpu-medium-core5-1",
        "cpu-medium-core6-0", "cpu-medium-core6-1", "cpu-big-core7-0", "cpu-big-core7-1",
        "cpu-dsu0", "cpu-dsu1", "cpu-dsu2", "cpu-dsu3",
    )
    private val SKIN_ZONES = (1..10).map { "vtskin$it" }.toSet()
    private val SOC_ZONES = setOf("soc_max") + (0..3).flatMap { listOf("soc-top$it", "soc-bot$it") }
}