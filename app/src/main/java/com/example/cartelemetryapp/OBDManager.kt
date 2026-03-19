package com.example.cartelemetryapp
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TelemetryData(
    val rpm: Int? = null,
    val speed: Int? = null,
    val coolantTemp: Int? = null,
    val throttlePosition: Int? = null,
    val engineLoad: Int? = null,
    val fuelLevel: Int? = null
)
class OBDManager(private val context: Context) {

    companion object {
        private const val TAG = "OBD"
        private val OBD_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val commandMutex = Mutex()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /* ===================== PUBLIC API ===================== */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startTrip(onTelemetryUpdate: (TelemetryData) -> Unit) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Bluetooth permission not granted")
            return
        }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                connect()
                initializeOBD()
                readTelemetryLoop(onTelemetryUpdate)
            } catch (e: Exception) {
                Log.e(TAG, "OBD error", e)
            }
        }
    }

    fun stopTrip() {
        scope.cancel()
        closeConnection()
    }

    /* ===================== CONNECTION ===================== */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun connect() = withTimeout(10000) {

        requireBluetoothPermission()

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth not supported")

        val device = findOBDDevice(adapter)
            ?: throw IllegalStateException("OBD device not paired")

        try {
            requireBluetoothPermission()
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
        }

        socket = device.createRfcommSocketToServiceRecord(OBD_UUID)
        Log.d(TAG, "Attempting Bluetooth connect...")
        socket!!.connect()

        input = socket!!.inputStream
        output = socket!!.outputStream
        delay(2000)
        Log.d(TAG, "Connected to OBD device")
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findOBDDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        requireBluetoothPermission()
        return adapter.bondedDevices.firstOrNull {
            it.name.contains("OBD", ignoreCase = true) ||
                    it.name.contains("MX", ignoreCase = true)
        }
    }

    /* ===================== OBD SETUP ===================== */

    private suspend fun initializeOBD() {

        sendCommand("ATZ")
        delay(2000)

        flushInputBuffer()

        sendCommand("ATE0")
        sendCommand("ATL0")
        sendCommand("ATS0")
        sendCommand("ATH0")
        sendCommand("ATSP6")

        delay(1500)      // allow auto protocol detect

        sendCommand("ATDP")  //  FORCE protocol confirmation
        delay(500)
        val test = sendCommand("0100")
        val normalized = test.replace(" ", "")
        if (!normalized.contains("4100")) {
            throw IllegalStateException("ECU not responding")
        }
        flushInputBuffer()
    }

    /* ===================== RPM LOOP ===================== */

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readTelemetryLoop(
        onTelemetryUpdate: (TelemetryData) -> Unit
    ) {

        var lastGoodData = TelemetryData()

        while (currentCoroutineContext().isActive) {

            try {

                val rpm = safePid("010C") { parseRpm(it) }
                val speed = safePid("010D") { parseSpeed(it) }
                val coolant = safePid("0105") { parseCoolantTemp(it) }
                val throttle = safePid("0111") { parseThrottle(it) }
                val engineLoad = safePid("0104") { parseEngineLoad(it) }
                val fuel = safePid("012F") { parseFuelLevel(it) }

                val newData = TelemetryData(
                    rpm = rpm ?: lastGoodData.rpm,
                    speed = speed ?: lastGoodData.speed,
                    coolantTemp = coolant ?: lastGoodData.coolantTemp,
                    throttlePosition = throttle ?: lastGoodData.throttlePosition,
                    engineLoad = engineLoad ?: lastGoodData.engineLoad,
                    fuelLevel = fuel ?: lastGoodData.fuelLevel
                )

                lastGoodData = newData
                onTelemetryUpdate(newData)

                delay(1000)

            } catch (fatal: Exception) {

                Log.e(TAG, "Fatal OBD failure — reconnecting", fatal)
                performReconnect()
            }
        }
    }

    private suspend fun <T> safePid(
        command: String,
        parser: (String) -> T?
    ): T? {
        return try {
            val response = sendCommand(command)
            //Log.d("RAW_OBD", "CMD=$command RESP=$response")

            if (response.contains("NO DATA", ignoreCase = true)) {
                Log.e("OBD", "ECU returned NO DATA for $command")
            }

            parser(response)
        } catch (e: Exception) {
            Log.w(TAG, "PID $command failed: ${e.message}")
            null
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun performReconnect() {

        closeConnection()

        repeat(3) { attempt ->
            try {
                delay(2000)
                connect()
                initializeOBD()
                Log.d(TAG, "Reconnect successful")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect attempt ${attempt + 1} failed", e)
            }
        }

        Log.e(TAG, "All reconnect attempts failed — stopping trip")
        throw IllegalStateException("Unable to reconnect to OBD")
    }

    /* ===================== COMMANDS ===================== */

    private suspend fun sendCommand(command: String): String =
        commandMutex.withLock {

            if (socket?.isConnected != true) {
                throw IllegalStateException("Socket not connected")
            }

            withTimeout(2500) {

                output?.write("$command\r".toByteArray())
                    ?: throw IllegalStateException("Output stream null")

                output?.flush()

                delay(50) // 100ms is a bit high for MX+

                readResponse()
            }
        }

    private suspend fun readResponse(): String = withTimeout(2000) {
        val buffer = StringBuilder()
        val temp = ByteArray(256)

        withContext(Dispatchers.IO) {
            while (true) {
                val bytes = input?.read(temp) ?: break
                if (bytes <= 0) break

                val chunk = String(temp, 0, bytes)
                buffer.append(chunk)

                if (chunk.contains(">")) break
            }
        }

        buffer.toString()
            .replace("\r", "")
            .replace("\n", "")
            .replace(">", "")
            .trim()
    }

    private suspend fun flushInputBuffer() {
        withContext(Dispatchers.IO) {
            while (input?.available() ?: 0 > 0) {
                input?.read(ByteArray(256))
            }
        }
    }

    /* ===================== PARSING and CLEANING ===================== */

    private fun parseRpm(response: String): Int? {
        val parts = cleanResponse(response, "0C") ?: return null
        if (parts.size < 2) return null
        return ((parts[0] * 256) + parts[1]) / 4
    }
    private fun parseCoolantTemp(response: String): Int? {
        val parts = cleanResponse(response, "05") ?: return null
        return parts[0] - 40
    }

    private fun parseSpeed(response: String): Int? {
        val parts = cleanResponse(response, "0D") ?: return null
        return parts[0]
    }

    private fun parseThrottle(response: String): Int? {
        val parts = cleanResponse(response, "11") ?: return null
        return (parts[0] * 100) / 255
    }
    private fun parseEngineLoad(response: String): Int? {
        val parts = cleanResponse(response, "04") ?: return null
        return (parts[0] * 100) / 255
    }
    private fun parseFuelLevel(response: String): Int? {
        val parts = cleanResponse(response, "2F") ?: return null
        return if (parts.isNotEmpty()) (parts[0] * 100) / 255 else null
    }

    private fun cleanResponse(response: String, pid: String): List<Int>? {

        val hex = response
            .replace("\r", "")
            .replace("\n", "")
            .replace(">", "")
            .replace(" ", "")
            .trim()
            .uppercase()

        val expectedPrefix = "41$pid"

        val index = hex.indexOf(expectedPrefix)
        if (index == -1) return null

        val dataStart = index + expectedPrefix.length
        if (dataStart >= hex.length) return null

        val dataHex = hex.substring(dataStart)

        if (dataHex.length < 2) return null

        val bytes = mutableListOf<Int>()
        for (i in dataHex.indices step 2) {
            if (i + 2 <= dataHex.length) {
                val byte = dataHex.substring(i, i + 2).toIntOrNull(16)
                if (byte != null) bytes.add(byte)
            }
        }

        return if (bytes.isEmpty()) null else bytes
    }


    /* ===================== CLEANUP ===================== */

    private fun closeConnection() {
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (_: Exception) {}
    }

    /* ===================== PERMISSIONS ===================== */

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun requireBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("BLUETOOTH_CONNECT not granted")
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("BLUETOOTH_SCAN not granted")
            }
        }
    }
}