package com.example.aispeechassistant

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log

class GestureDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdate: Long = 0

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Ensure we have a valid event
        if (event == null) return

        val curTime = System.currentTimeMillis()
        // Only process if enough time has passed since the last update
        if ((curTime - lastUpdate) > 100) {
            val diffTime = curTime - lastUpdate
            lastUpdate = curTime

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Use a separate function to calculate device speed based on sensor values
            val speed = calculateSpeed(x, y, z, lastX, lastY, lastZ, diffTime)

            // If speed exceeds the threshold, log the shake event (or trigger another action)
            if (speed > 800) {
                Log.d("GestureDetectionService", "Device shook!")
                // Additional action can be taken here
            }

            // Update the last known coordinates
            lastX = x
            lastY = y
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used here, but required by the SensorEventListener interface
    }

    /**
     * Calculate the speed of the device based on its current and previous sensor values.
     *
     * @param x Current X value from the accelerometer
     * @param y Current Y value from the accelerometer
     * @param z Current Z value from the accelerometer
     * @param lastX Previous X value
     * @param lastY Previous Y value
     * @param lastZ Previous Z value
     * @param diffTime Time difference between sensor updates
     * @return The calculated speed value
     */
    private fun calculateSpeed(
        x: Float,
        y: Float,
        z: Float,
        lastX: Float,
        lastY: Float,
        lastZ: Float,
        diffTime: Long
    ): Float {
        return Math.abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000
    }
}