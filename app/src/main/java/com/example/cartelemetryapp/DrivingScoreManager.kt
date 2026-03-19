package com.example.cartelemetryapp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.abs

class DrivingScoreManager {

    private val WINDOW_SIZE = 24  // ~1 minute if updates every 2 sec

    private val speeds = mutableListOf<Double>()
    private val rpmEvents = mutableListOf<Int>()
    private val loadEvents = mutableListOf<Int>()
    private val accelEvents = mutableListOf<Int>()
    private val throttleEvents = mutableListOf<Int>()
    private val oscillationEvents = mutableListOf<Int>()

    private var lastSpeed = 0.0

    fun reset() {
        speeds.clear()
        rpmEvents.clear()
        loadEvents.clear()
        accelEvents.clear()
        throttleEvents.clear()
        oscillationEvents.clear()
        lastSpeed = 0.0
    }

    fun update(
        speed: Double,
        rpm: Int,
        engineLoad: Double,
        throttle: Double
    ): Int {

        // --- SPEED HISTORY ---
        speeds.add(speed)
        if (speeds.size > WINDOW_SIZE) speeds.removeAt(0)

        // --- RPM EVENT ---
        rpmEvents.add(if (rpm > 3500) 1 else 0)
        if (rpmEvents.size > WINDOW_SIZE) rpmEvents.removeAt(0)

        // --- ENGINE LOAD EVENT ---
        loadEvents.add(if (engineLoad > 70) 1 else 0)
        if (loadEvents.size > WINDOW_SIZE) loadEvents.removeAt(0)

        // --- THROTTLE EVENT ---
        throttleEvents.add(if (throttle > 60) 1 else 0)
        if (throttleEvents.size > WINDOW_SIZE) throttleEvents.removeAt(0)

        // --- HARD ACCELERATION ---
        val accel = speed - lastSpeed
        accelEvents.add(if (lastSpeed > 5 && abs(accel) > 7) 1 else 0)
        if (accelEvents.size > WINDOW_SIZE) accelEvents.removeAt(0)

        // --- SPEED OSCILLATION ---
        val delta = kotlin.math.abs(speed - lastSpeed)
        oscillationEvents.add(if (delta > 10) 1 else 0)
        if (oscillationEvents.size > WINDOW_SIZE) oscillationEvents.removeAt(0)

        lastSpeed = speed

        // --- CALCULATE METRICS ---

        val highRpmPct = rpmEvents.sum().toDouble() / rpmEvents.size.coerceAtLeast(1)
        val highLoadPct = loadEvents.sum().toDouble() / loadEvents.size.coerceAtLeast(1)
        val throttlePct = throttleEvents.sum().toDouble() / throttleEvents.size.coerceAtLeast(1)
        val hardAccelCount = accelEvents.sum()
        val oscillationCount = oscillationEvents.sum()

        // --- SPEED VARIABILITY (STANDARD DEVIATION) ---

        val avgSpeed = speeds.average()

        val speedVariance = speeds.map {
            (it - avgSpeed) * (it - avgSpeed)
        }.average()

        val speedStdDev = kotlin.math.sqrt(speedVariance)

        // --- NORMALIZE TO 0-100 PENALTIES ---

        val rpmScore = minOf(100.0, (highRpmPct / 0.30) * 100)
        val loadScore = minOf(100.0, (highLoadPct / 0.40) * 100)
        val throttleScore = minOf(100.0, (throttlePct / 0.30) * 100)

        val accelScore = minOf(100.0, hardAccelCount * 12.0)
        val oscillationScore = minOf(100.0, oscillationCount * 10.0)

        val speedVarScore = minOf(100.0, speedStdDev * 6)

        // --- WEIGHTED AGGRESSIVENESS SCORE ---

        val aggressiveness =
            0.20 * rpmScore +
                    0.20 * loadScore +
                    0.20 * throttleScore +
                    0.20 * accelScore +
                    0.10 * speedVarScore +
                    0.10 * oscillationScore

        // --- FINAL DRIVING SCORE ---

        val drivingScore = (100 - aggressiveness).coerceIn(0.0, 100.0)

        return drivingScore.toInt()
    }
}