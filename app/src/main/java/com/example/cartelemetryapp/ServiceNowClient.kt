package com.example.cartelemetryapp

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import com.example.cartelemetryapp.BuildConfig



object ServiceNowClient {

    private const val INSTANCE = BuildConfig.SERVICENOW_INSTANCE
    private const val USERNAME = BuildConfig.SERVICENOW_USERNAME
    private const val PASSWORD = BuildConfig.SERVICENOW_PASSWORD

    fun fetchTripHistory(): List<TripSummary> {

        val url = URL(
            "$INSTANCE/api/now/table/u_trip_summary?sysparm_query=ORDERBYDESCsys_created_on"
        )

        val connection = url.openConnection() as HttpURLConnection

        val auth = "$USERNAME:$PASSWORD"
        val encodedAuth =
            Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)

        connection.setRequestProperty(
            "Authorization",
            "Basic $encodedAuth"
        )

        connection.setRequestProperty("Accept", "application/json")

        val response =
            connection.inputStream.bufferedReader().readText()

        val json = JSONObject(response)
        val results = json.getJSONArray("result")

        val trips = mutableListOf<TripSummary>()

        Log.d("Trip Count", "Trips Fetched: ${results.length()}")

        for (i in 0 until results.length()) {

            val item = results.getJSONObject(i)

            trips.add(
                TripSummary(
                    startTime = item.getString("u_start_time"),
                    endTime = item.getString("u_end_time"),
                    distanceMiles = item.optString("u_distance_miles")?.toDoubleOrNull() ?: 0.0,
                    durationMinutes = item.optString("u_duration_minutes")?.toDoubleOrNull() ?: 0.0,
                    avgSpeed = item.optString("u_avg_speed_mph")?.toDoubleOrNull() ?: 0.0,
                    aggressivenessScore = item.optString("u_aggressiveness_score").toDoubleOrNull(),
                    fuelUsedPercent = item.optString("u_fuel_used_percent").toDoubleOrNull()
                )
            )
        }

        return trips
    }
}