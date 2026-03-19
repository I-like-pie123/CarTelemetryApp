package com.example.cartelemetryapp

data class TripSummary(
    val startTime: String,
    val endTime: String,
    val distanceMiles: Double,
    val durationMinutes: Double,
    val avgSpeed: Double,
    val aggressivenessScore: Double?,
    val fuelUsedPercent: Double?
)
