package com.termx.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Sensor data provider for TermX API.
 * Similar to termux-sensor.
 *
 * Supports: accelerometer, gyroscope, proximity, light, pressure,
 * magnetometer, humidity, temperature, step counter, etc.
 */
object SensorProvider {

    private const val TAG = "SensorProvider"

    data class SensorData(
        val name: String,
        val type: Int,
        val vendor: String,
        val version: Int,
        val values: List<Float>,
        val unit: String
    ) {
        fun toFormattedString(): String {
            return buildString {
                appendLine("$name ($type) by $vendor v$version")
                if (values.isNotEmpty()) {
                    val formattedValues = values.mapIndexed { i, v ->
                        "  Value${i}: ${"%.4f".format(v)} $unit"
                    }
                    appendLine(formattedValues.joinToString("\n"))
                }
            }
        }
    }

    /**
     * List all available sensors on the device.
     */
    fun listSensors(context: Context): List<String> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            "${sensor.name} (type=${sensor.type}, vendor=${sensor.vendor})"
        }
    }

    /**
     * Get sensor info as formatted string.
     */
    fun getSensorInfo(context: Context): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)

        return buildString {
            appendLine("=== Available Sensors (${sensors.size}) ===")
            for (sensor in sensors) {
                appendLine("  ${sensor.name}")
                appendLine("    Type: ${sensorTypeToString(sensor.type)}")
                appendLine("    Vendor: ${sensor.vendor}")
                appendLine("    Range: ${sensor.maximumRange}")
                appendLine("    Resolution: ${sensor.resolution}")
                appendLine("    Power: ${sensor.power}mA")
                appendLine("    Min Delay: ${sensor.minDelay}μs")
            }
        }
    }

    /**
     * Read a single sensor value.
     */
    fun readSensor(
        context: Context,
        sensorType: Int,
        timeoutMs: Long = 3000,
        callback: (SensorData?) -> Unit
    ) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(sensorType)

        if (sensor == null) {
            callback(null)
            return
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sm.unregisterListener(this)
                callback(
                    SensorData(
                        name = sensor.name,
                        type = sensor.type,
                        vendor = sensor.vendor,
                        version = sensor.version,
                        values = event.values.toList(),
                        unit = getUnitForType(sensor.type)
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

        // Timeout
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sm.unregisterListener(listener)
        }, timeoutMs)
    }

    /**
     * Read all sensors once.
     */
    fun readAllSensors(context: Context, callback: (List<SensorData>) -> Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        val results = mutableListOf<SensorData>()
        var remaining = sensors.size

        if (sensors.isEmpty()) {
            callback(emptyList())
            return
        }

        for (sensor in sensors) {
            readSensor(context, sensor.type, 2000) { data ->
                data?.let { results.add(it) }
                remaining--
                if (remaining <= 0) {
                    callback(results)
                }
            }
        }
    }

    private fun sensorTypeToString(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_LIGHT -> "Light"
            Sensor.TYPE_PRESSURE -> "Pressure"
            Sensor.TYPE_PROXIMITY -> "Proximity"
            Sensor.TYPE_GRAVITY -> "Gravity"
            Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative Humidity"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temperature"
            Sensor.TYPE_STEP_COUNTER -> "Step Counter"
            Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game Rotation Vector"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope Uncalibrated"
            Sensor.TYPE_HEART_RATE -> "Heart Rate"
            else -> "Unknown ($type)"
        }
    }

    private fun getUnitForType(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> "m/s²"
            Sensor.TYPE_MAGNETIC_FIELD -> "μT"
            Sensor.TYPE_GYROSCOPE -> "rad/s"
            Sensor.TYPE_LIGHT -> "lux"
            Sensor.TYPE_PRESSURE -> "hPa"
            Sensor.TYPE_PROXIMITY -> "cm"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
            Sensor.TYPE_HEART_RATE -> "bpm"
            else -> ""
        }
    }
}
