package com.miaotiao.adskip.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 只在「应用刚启动的开屏窗口」及「跳转前后」采样，
 * 避免常驻监听耗电。
 */
class ShakeDetector(context: Context) : SensorEventListener {

    data class Features(
        val samples: Int,
        val peak: Float,
        val variance: Float,
        val reversals: Int
    ) {
        val isShake: Boolean
            get() = samples >= 10 && (peak >= 15f || (variance >= 4f && reversals >= 3))
    }

    private data class Sample(val t: Long, val v: Float)

    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val samples = ArrayDeque<Sample>()

    @Volatile
    private var listening = false

    private val stopRunnable = Runnable { stop() }

    fun start(windowMs: Long) {
        if (sensor == null) return
        if (!listening) {
            listening = sensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            ) == true
        }
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, windowMs)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val value = sqrt(x * x + y * y + z * z) - 9.81f
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, value))
        while (samples.isNotEmpty() && now - samples.first().t > 2000) {
            samples.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun features(): Features {
        val cutoff = System.currentTimeMillis() - 2000
        val values = samples.filter { it.t >= cutoff }.map { it.v }
        if (values.size < 10) return Features(values.size, 0f, 0f, 0)
        val peak = values.maxOf { abs(it) }
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        var reversals = 0
        var lastSign = 0
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            if (abs(d) < 0.5f) continue
            val sign = if (d > 0) 1 else -1
            if (lastSign != 0 && sign != lastSign) reversals++
            lastSign = sign
        }
        return Features(values.size, peak, variance, reversals)
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        if (listening) {
            sensorManager?.unregisterListener(this)
            listening = false
        }
    }
}
