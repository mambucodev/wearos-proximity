package dev.mambuco.watchproximity.presentation

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class LaptopProximityState(
    val connected: Boolean = false,
    val laptopName: String = "Freetop",
    val zone: String = "Scanning...",
    val rssi: Int? = null,
    val distance: String = "--",
    val guardEnabled: Boolean = true,
    val isLocked: Boolean = false,
    val laptopBattery: Int? = null,
    val acOnline: Boolean = true,
    val snoozeRemaining: Int = 0,
    val lastUpdate: Long = 0L,
    val statusMessage: String? = null
)

class WatchProximityViewModel {

    companion object {
        private const val TAG = "WatchProximityVM"
        private val HOST_CANDIDATES = listOf("127.0.0.1", "192.168.1.6")
        private const val PORT = 8999
    }

    var state by mutableStateOf(LaptopProximityState())
        private set

    private var activeHost: String = "127.0.0.1"
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refreshStatus()
                delay(1500)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshStatus() {
        scope.launch {
            for (host in HOST_CANDIDATES) {
                try {
                    val url = URL("http://$host:$PORT/api/status")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        requestMethod = "GET"
                    }

                    if (conn.responseCode == 200) {
                        val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                        activeHost = host
                        val json = JSONObject(response)

                        withContext(Dispatchers.Main) {
                            state = state.copy(
                                connected = true,
                                laptopName = json.optString("laptop", "Freetop"),
                                zone = json.optString("state", "NORMAL"),
                                rssi = if (json.has("rssi") && !json.isNull("rssi")) json.getInt("rssi") else null,
                                distance = json.optString("distance", "--"),
                                guardEnabled = json.optBoolean("proximity_enabled", true),
                                isLocked = json.optBoolean("is_locked", false),
                                laptopBattery = if (json.has("laptop_battery") && !json.isNull("laptop_battery")) json.getInt("laptop_battery") else null,
                                acOnline = json.optBoolean("ac_online", true),
                                snoozeRemaining = json.optInt("snooze_remaining", 0),
                                lastUpdate = System.currentTimeMillis()
                            )
                        }
                        conn.disconnect()
                        return@launch
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // Try next host
                }
            }

            // If both hosts fail
            withContext(Dispatchers.Main) {
                if (System.currentTimeMillis() - state.lastUpdate > 5000) {
                    state = state.copy(connected = false, zone = "Searching...")
                }
            }
        }
    }

    fun lockLaptop(onComplete: (Boolean) -> Unit = {}) {
        postAction("/api/lock") { success ->
            if (success) {
                state = state.copy(isLocked = true, statusMessage = "Screen locked")
            }
            onComplete(success)
        }
    }

    fun toggleGuard(onComplete: (Boolean) -> Unit = {}) {
        postAction("/api/toggle-guard") { success ->
            refreshStatus()
            onComplete(success)
        }
    }

    fun ringLaptop(onComplete: (Boolean) -> Unit = {}) {
        postAction("/api/ring") { success ->
            if (success) {
                state = state.copy(statusMessage = "Ringing Freetop!")
            }
            onComplete(success)
        }
    }

    fun snoozeGuard(durationSec: Int = 900, onComplete: (Boolean) -> Unit = {}) {
        postAction("/api/snooze?duration=$durationSec") { success ->
            refreshStatus()
            onComplete(success)
        }
    }

    private fun postAction(endpoint: String, callback: (Boolean) -> Unit) {
        scope.launch {
            for (host in listOf(activeHost) + HOST_CANDIDATES.filter { it != activeHost }) {
                try {
                    val url = URL("http://$host:$PORT$endpoint")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1200
                        readTimeout = 1200
                        requestMethod = "POST"
                        doOutput = true
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) {
                        activeHost = host
                        withContext(Dispatchers.Main) { callback(true) }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error POST $endpoint to $host: $e")
                }
            }
            withContext(Dispatchers.Main) { callback(false) }
        }
    }
}
