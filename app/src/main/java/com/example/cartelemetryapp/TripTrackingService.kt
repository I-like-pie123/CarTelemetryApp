package com.example.cartelemetryapp


import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import org.json.JSONObject
import com.google.android.gms.location.*
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.app.Service
import android.os.IBinder

class TripTrackingService : Service() {

    private lateinit var obdManager: OBDManager
    private lateinit var locationClient: FusedLocationProviderClient

    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0.0
    private var lastTelemetryUpload = 0L

    private var latestRpm: Int? = null
    private var latestThrottle: Double? = null
    private var latestEngineLoad: Double? = null
    private var latestCoolantTemp: Double? = null
    private var latestEcuSpeed: Double? = null
    private val SHEET_UPDATE_INTERVAL = 5_000L
    private val SHEET_URL = BuildConfig.SHEETS_URL
    private var startFuelLevel: Int? = null
    private var latestFuelLevel: Int? = null
    private var lastSpeedMph=0.0
    private lateinit var scoreManager: DrivingScoreManager
    private var isRunning=false
    private var currentTripId: String? = null
    private var isPaused = false
    private var belowSpeedStartTime: Long? = null

    private val AUTO_PAUSE_THRESHOLD_MPH = 2.0
    private val AUTO_RESUME_THRESHOLD_MPH = 5.0
    private val AUTO_PAUSE_DELAY_MS = 60_000L // 60 seconds

    override fun onCreate() {
        super.onCreate()
        obdManager = OBDManager(this)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        scoreManager = DrivingScoreManager()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w("SERVICE", "Restarted with null intent")
            return START_STICKY
        }
        intent.getStringExtra("action")?.let { action ->
            when (action) {
                "PAUSE" -> {
                    isPaused = true
                    Log.d("SERVICE", "Paused")
                    return START_STICKY
                }
                "RESUME" -> {
                    isPaused = false
                    Log.d("SERVICE", "Resumed")
                    return START_STICKY
                }
            }
        }

        Log.d("SERVICE_LIFECYCLE", "onStartCommand called")
        currentTripId = intent.getStringExtra("trip_id")

        startForeground(1, createNotification())

        // OBD START
        obdManager.startTrip { telemetry ->

            latestRpm = telemetry.rpm
            latestThrottle = telemetry.throttlePosition?.toDouble()
            latestEngineLoad = telemetry.engineLoad?.toDouble()
            latestCoolantTemp = telemetry.coolantTemp?.toDouble()
            latestEcuSpeed = telemetry.speed?.toDouble()

            latestFuelLevel = telemetry.fuelLevel

            if (startFuelLevel == null && telemetry.fuelLevel != null) {
                startFuelLevel = telemetry.fuelLevel
            }

            Log.d("SERVICE_OBD", "RPM=$latestRpm Fuel=$latestFuelLevel")
        }

        // GPS START
        val locationRequest = LocationRequest.create().apply {
            interval = 2000L
            fastestInterval = 1000L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        if (isRunning) return START_STICKY
        isRunning=true

        return START_STICKY
    }



    private val locationCallback = object : LocationCallback() {

        override fun onLocationResult(result: LocationResult) {

            if (isPaused) return

            val location = result.lastLocation ?: return

            val latitude = location.latitude
            val longitude = location.longitude

            val speedMps = location.speed
            var speedMph = speedMps * 2.23694

            val now = System.currentTimeMillis()


            if (!isPaused) {
                if (speedMph < AUTO_PAUSE_THRESHOLD_MPH) {
                    if (belowSpeedStartTime == null) {
                        belowSpeedStartTime = now
                    } else if (now - belowSpeedStartTime!! >= AUTO_PAUSE_DELAY_MS) {
                        isPaused = true
                        Log.d("AUTO_PAUSE", "Trip auto-paused")

                        broadcastPauseState(true)
                    }
                } else {
                    belowSpeedStartTime = null
                }
            }


            if (isPaused && speedMph > AUTO_RESUME_THRESHOLD_MPH) {
                isPaused = false
                belowSpeedStartTime = null

                Log.d("AUTO_RESUME", "Trip auto-resumed")

                broadcastPauseState(false)
            }

            // smoothing logic
            if (speedMph < 1 && lastLocation != null) {
                val timeDiff = location.time - lastLocation!!.time
                if (timeDiff < 4000) {
                    speedMph=lastSpeedMph
                }
            }
            lastSpeedMph=speedMph

            val timestamp = System.currentTimeMillis()

            //  Distance tracking
            if (!isPaused && lastLocation != null) {
                val distance = lastLocation!!.distanceTo(location)
                totalDistanceMeters += distance
            }
            lastLocation = location

            // Driving score
            val score = if (!isPaused && latestRpm != null) {
                scoreManager.update(
                    speed = latestEcuSpeed ?: speedMph,
                    rpm = latestRpm!!,
                    engineLoad = latestEngineLoad ?: 0.0,
                    throttle = latestThrottle ?: 0.0
                )
            } else null

            Log.d("SERVICE_GPS", "Location Update received")
            //  Build JSON
            val json = JSONObject().apply {
                put("type", "telemetry")
                put("trip_id", currentTripId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("gps_speed_mph", speedMph)
                put("ecu_speed_mph", latestEcuSpeed ?: JSONObject.NULL)
                put("distance_meters", totalDistanceMeters)
                put("timestamp", timestamp)

                put("rpm", latestRpm ?: JSONObject.NULL)
                put("throttle_position", latestThrottle ?: JSONObject.NULL)
                put("engine_load", latestEngineLoad ?: JSONObject.NULL)
                put("coolant_temp_c", latestCoolantTemp ?: JSONObject.NULL)
            }


            if (now - lastTelemetryUpload >= SHEET_UPDATE_INTERVAL) {
                sendJSONToSheets(json.toString())
                lastTelemetryUpload = now
            }

            //Log.d("SERVICE_GPS", "Dist=$totalDistanceMeters Score=$score")
            val distanceMiles = totalDistanceMeters / 1609.34

            broadcastUpdate(
                speed = speedMph,
                distanceMiles = distanceMiles,
                rpm = latestRpm,
                engineLoad = latestEngineLoad,
                score = score
            )
        }
    }

    private fun sendJSONToSheets(json: String) {
        Thread {
            try {
                val url = java.net.URL(SHEET_URL)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                connection.outputStream.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }

                connection.inputStream.use { it.readBytes() }
                connection.disconnect()

            } catch (e: Exception) {
                Log.e("SERVICE_UPLOAD", "Upload failed", e)
            }
        }.start()
    }

    private fun createNotification(): Notification {
        val channelId = "trip_tracking"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Trip Tracking",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("Trip Running")
            .setContentText("Tracking in background")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        obdManager.stopTrip()
        locationClient.removeLocationUpdates(locationCallback)
    }

    private fun broadcastPauseState(paused: Boolean) {
        val intent = Intent("TRIP_UPDATE").apply {
            putExtra("isPaused", paused)
        }
        sendBroadcast(intent)
    }
    private fun broadcastUpdate(
        speed: Double,
        distanceMiles: Double,
        rpm: Int?,
        engineLoad: Double?,
        score: Int?
    ) {
        val intent = Intent("TRIP_UPDATE").apply {
            setPackage(packageName)
            putExtra("speed", speed)
            putExtra("distance", distanceMiles)
            putExtra("rpm", rpm ?: -1)
            putExtra("engineLoad", engineLoad ?: Double.NaN)
            putExtra("score", score ?: -1)
            putExtra("isPaused", isPaused)
        }
        sendBroadcast(intent)
        Log.d("SERVICE_BROADCAST", "Broadcast sent: speed=$speed distance=$distanceMiles rpm=$rpm engineLoad=$engineLoad score=$score")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}