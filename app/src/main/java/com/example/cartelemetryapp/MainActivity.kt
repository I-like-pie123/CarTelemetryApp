package com.example.cartelemetryapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val SHEET_UPDATE_INTERVAL = 5_000L // 5 seconds
    // GPS client
    private lateinit var locationClient: FusedLocationProviderClient
    private val SHEET_URL = "https://script.google.com/macros/s/AKfycbyKFJMn1HvDh6xUV_71m7FNgSWmUPI0nG2Ztp8Q3649qPlKqkXHN9IeQ5BjN-wt9sAm/exec"
    // Tracking state
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0.0

    // Compose state variables
    private val speedState = mutableStateOf(0.0)
    private val distanceState = mutableStateOf(0.0)

    private val scoreState = mutableStateOf(100)

    private val rpmState = mutableStateOf<Int?>(null)
    private val engineLoadState = mutableStateOf<Double?>(null)
    private var tripStartTime = 0L
    private var tripEndTime = 0L
    private var lastTelemetryUpload = 0L
    private lateinit var obdManager: OBDManager
    private var latestRpm: Int? = null
    private var latestThrottle: Double? = null
    private var latestEngineLoad: Double? = null
    private var latestCoolantTemp: Double? = null
    private var latestEcuSpeed: Double? = null
    private val REQUEST_BT_PERMISSIONS = 1001
    private var currentTripId: String? = null
    private var startFuelLevel: Int?=null
    private var latestFuelLevel: Int?=null


    private val scoreManager = DrivingScoreManager()

    enum class TripState {
        IDLE,
        RUNNING,
        PAUSED
    }


    private val tripState = mutableStateOf(TripState.IDLE)


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        obdManager = OBDManager(this)
        ensureBluetoothPermissions()

        // Initialize GPS client
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocationPermissions()
        //startLocationUpdates()
        registerReceiver(tripUpdateReceiver, IntentFilter("TRIP_UPDATE"), Context.RECEIVER_EXPORTED)
        Log.d("SERVICE_LIFECYCLE", "service created")
        // Compose UI
        setContent {


            var showHistory by rememberSaveable { mutableStateOf(false) }

            MaterialTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    if (showHistory) {

                        TripHistoryScreen(
                            onBack = { showHistory = false }
                        )

                    } else {

                        MainScreen(
                            speedState = speedState,
                            distanceState = distanceState,
                            rpmState = rpmState,
                            engineLoadState = engineLoadState,
                            tripState = tripState,
                            scoreState = scoreState,

                            onShowHistory = {
                                showHistory = true
                            },

                            onStartPauseResume = {

                                when (tripState.value) {

                                    TripState.IDLE -> {

                                        tripStartTime = System.currentTimeMillis()
                                        currentTripId = UUID.randomUUID().toString()

                                        val startJson = JSONObject().apply {
                                            put("type", "start_trip")
                                            put("trip_id", currentTripId)
                                            put("timestamp", System.currentTimeMillis())
                                        }

                                        sendJSONToSheets(startJson.toString())

                                        scoreManager.reset()

                                        if (!hasBluetoothPermission()) {
                                            ensureBluetoothPermissions()
                                            Toast.makeText(
                                                this,
                                                "Bluetooth permission required",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@MainScreen
                                        }

                                        //  START SERVICE HERE
                                        val intent = Intent(this, TripTrackingService::class.java)
                                        intent.putExtra("trip_id", currentTripId)
                                        ContextCompat.startForegroundService(applicationContext, intent)

                                        tripState.value = TripState.RUNNING
                                    }

                                    TripState.RUNNING -> {
                                        val intent = Intent(this, TripTrackingService::class.java)
                                        intent.putExtra("action", "PAUSE")
                                        ContextCompat.startForegroundService(this, intent)

                                        tripState.value = TripState.PAUSED
                                    }

                                    TripState.PAUSED -> {
                                        val intent = Intent(this, TripTrackingService::class.java)
                                        intent.putExtra("action", "RESUME")
                                        startService(intent)
                                        ContextCompat.startForegroundService(this, intent)

                                        tripState.value = TripState.RUNNING
                                    }
                                }

                            },

                            onEndTrip = {

                                if (tripState.value != TripState.IDLE) {

                                    // STOP SERVICE FIRST
                                    stopService(Intent(this, TripTrackingService::class.java))

                                    tripEndTime = System.currentTimeMillis()

                                    val durationMinutes =
                                        (tripEndTime - tripStartTime) / 60000.0

                                    val fuelUsedPercent =
                                        if (startFuelLevel != null && latestFuelLevel != null)
                                            startFuelLevel!! - latestFuelLevel!!
                                        else null

                                    val tripId = currentTripId

                                    if (tripId != null) {
                                        endTripAndShowSummary(tripId, fuelUsedPercent)
                                    }

                                    // reset UI state
                                    latestRpm = null
                                    latestThrottle = null
                                    latestEngineLoad = null
                                    latestCoolantTemp = null
                                    latestEcuSpeed = null
                                    latestFuelLevel = null
                                    startFuelLevel = null
                                    lastTelemetryUpload = 0L

                                    tripState.value = TripState.IDLE
                                    totalDistanceMeters = 0.0
                                    distanceState.value = 0.0
                                    speedState.value = 0.0
                                    lastLocation = null
                                    currentTripId = null
                                    rpmState.value = null
                                    engineLoadState.value = null
                                    scoreState.value = 100
                                    scoreManager.reset()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )

            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    missing.toTypedArray(),
                    REQUEST_BT_PERMISSIONS
                )
            }
        }
    }
    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
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
                Log.e("SHEETS_UPLOAD", "Upload failed", e)
            }
        }.start()
    }

    private val tripUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            val paused = intent?.getBooleanExtra("isPaused", false)

            if (tripState.value != TripState.IDLE) {
                tripState.value = if (paused == true) {
                    TripState.PAUSED
                } else {
                    TripState.RUNNING
                }
            }
            if (intent?.action == "TRIP_UPDATE") {

                speedState.value = intent.getDoubleExtra("speed", 0.0)
                distanceState.value = intent.getDoubleExtra("distance", 0.0)

                val rpmVal= intent.getIntExtra("rpm",-1)
                rpmState.value=if (rpmVal==-1) null else rpmVal


                engineLoadState.value =
                    intent.getDoubleExtra("engineLoad", Double.NaN).takeIf { !it.isNaN() }

                val scoreVal= intent.getIntExtra("score", -1)
                if (scoreVal!=-1){
                    scoreState.value=scoreVal
                }
            }
            Log.d("UI_RECEIVER", "Broadcast received")
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(tripUpdateReceiver)
    }

    private fun requestLocationPermissions() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            return
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    1002
                )
            }
        }
    }





    private fun endTripAndShowSummary(tripId: String, fuelUsedPercent: Int?) {

        Thread {
            try {
                val url = java.net.URL(SHEET_URL)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                Log.d("TRIP_UPLOAD", "Sending telemetry to server")

                val json = """
                {
                  "type": "end_trip",
                  "trip_id": "$tripId",
                  "fuel_used_percent": ${fuelUsedPercent ?: "null"}
                }
            """.trimIndent()

                connection.outputStream.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()
                if (!response.trim().startsWith("{")) {
                    Log.e("SERVER_RESPONSE", response)
                    return@Thread
                }
                val obj = org.json.JSONObject(response)
                if (obj.has("error")) {
                    Log.e("SERVER_ERROR", obj.getString("error"))
                    return@Thread
                }

                val distance = obj.getDouble("distance_miles")
                val duration = obj.getDouble("duration_minutes")
                val avgSpeed = obj.getDouble("avg_speed_mph")
                val aggressiveness = obj.getDouble("aggressiveness_score")

                runOnUiThread {
                    showTripSummaryDialog(
                        distance,
                        duration,
                        avgSpeed,
                        aggressiveness
                    )
                }
                Log.d("TRIP_UPLOAD", "Server Response: $response")

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
    private fun showTripSummaryDialog(
        distance: Double,
        duration: Double,
        avgSpeed: Double,
        aggressiveness: Double
    ) {

        val message = """
        Distance: ${"%.2f".format(distance)} miles
        Duration: ${"%.1f".format(duration)} minutes
        Avg Speed: ${"%.1f".format(avgSpeed)} mph
        Aggressiveness: ${aggressiveness.toInt()}
    """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Trip Summary")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}

@Composable
fun MainScreen(
    speedState: MutableState<Double>,
    distanceState: MutableState<Double>,
    rpmState: MutableState<Int?>,
    engineLoadState: MutableState<Double?>,
    scoreState: MutableState<Int>,
    tripState: MutableState<MainActivity.TripState>,
    onShowHistory: () -> Unit,
    onStartPauseResume: () -> Unit,
    onEndTrip: () -> Unit
) {
    val speed= speedState.value
    val score= scoreState.value
    val rpm = rpmState.value
    val engineLoad = engineLoadState.value
    val distance=distanceState.value
    val tripStateValue=tripState.value
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        //Text ("Debug speed: ${speedState.value}")
        //Text ("debug distance: ${distanceState.value}")

        Text(
            text = "Driving Score: $score",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                score > 85 -> Color(0xFF00AA00)
                score > 65 -> Color(0xFFFFC107)
                else -> Color.Red
            }
        )

        Text(
            text = "Speed: %.1f mph".format(speed),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Distance: %.2f miles".format(distance),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "RPM: ${rpm ?: "--"}",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Engine Load: ${engineLoad?.toInt() ?: "--"}%",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        val startButtonText = when (tripStateValue) {
            MainActivity.TripState.IDLE -> "Start Trip"
            MainActivity.TripState.RUNNING -> "Pause"
            MainActivity.TripState.PAUSED -> "Resume"
        }

        Button(onClick = onStartPauseResume) {
            Text(startButtonText)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tripStateValue != MainActivity.TripState.IDLE) {
            Button(onClick = onEndTrip) {
                Text("End Trip")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onShowHistory
        ) {
            Text("Trip History")
        }

    }
}