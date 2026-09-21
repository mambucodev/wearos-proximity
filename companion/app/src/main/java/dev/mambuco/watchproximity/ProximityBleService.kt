package dev.mambuco.watchproximity

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.charset.StandardCharsets
import java.util.*

class ProximityBleService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "WatchProximity"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val ALARM_NOTIFICATION_ID = 1003
        private const val CHANNEL_SERVICE = "channel_proximity_service"
        private const val CHANNEL_ALERTS = "channel_proximity_alerts"
        private const val CHANNEL_ALARM = "channel_proximity_alarm"

        val SERVICE_UUID: UUID = UUID.fromString("0000f00d-0000-1000-8000-00805f9b34fb")
        val CHAR_STATUS_UUID: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_LOCK = 1
        const val CMD_UNLOCK = 2
        const val CMD_ALARM = 3
        const val CMD_ALARM_STOP = 4
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var sensorManager: SensorManager? = null
    private var vibrator: Vibrator? = null
    private var keyguardManager: KeyguardManager? = null

    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    private var isOnBody = true
    private var isDeviceLocked = false
    private var batteryPct = 100
    private var isScreenOn = true

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Device unlocked by user")
                    updateLockState()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateLockState()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateLockState()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = (level * 100) / scale
                        notifyStatusChanged()
                    }
                }
                "dev.mambuco.watchproximity.ACTION_LOCK" -> {
                    Log.i(TAG, "Received ACTION_LOCK broadcast")
                    onLaptopLocked()
                }
                "dev.mambuco.watchproximity.ACTION_UNLOCK" -> {
                    Log.i(TAG, "Received ACTION_UNLOCK broadcast")
                    onLaptopUnlocked()
                }
                "dev.mambuco.watchproximity.ACTION_ALARM" -> {
                    Log.i(TAG, "Received ACTION_ALARM broadcast")
                    onAlarmTriggered()
                }
                "dev.mambuco.watchproximity.ACTION_ALARM_STOP" -> {
                    Log.i(TAG, "Received ACTION_ALARM_STOP broadcast")
                    onAlarmStopped()
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed with error: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE Device disconnected: ${device.address}")
                subscribedDevices.remove(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_STATUS_UUID) {
                val data = getStatusJson().toByteArray(StandardCharsets.UTF_8)
                val response = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == CHAR_COMMAND_UUID && value != null && value.isNotEmpty()) {
                handleCommand(value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.add(device)
                    Log.i(TAG, "Device subscribed to notifications: ${device.address}")
                } else {
                    subscribedDevices.remove(device)
                    Log.i(TAG, "Device unsubscribed: ${device.address}")
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ProximityBleService starting...")

        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildForegroundNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction("dev.mambuco.watchproximity.ACTION_LOCK")
            addAction("dev.mambuco.watchproximity.ACTION_UNLOCK")
            addAction("dev.mambuco.watchproximity.ACTION_ALARM")
            addAction("dev.mambuco.watchproximity.ACTION_ALARM_STOP")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )

        registerSensors()
        setupGattServer()
        startAdvertising()
        updateLockState()
    }

    private fun registerSensors() {
        // Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT = 34
        val offbodySensor = sensorManager?.getDefaultSensor(34)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (offbodySensor != null) {
            sensorManager?.registerListener(this, offbodySensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Registered off-body sensor: ${offbodySensor.name}")
        } else {
            Log.w(TAG, "Low latency off-body sensor not found, falling back to on-wrist assumption")
        }
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val statusChar = BluetoothGattCharacteristic(
            CHAR_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusChar.addDescriptor(cccd)
        service.addCharacteristic(statusChar)

        val commandChar = BluetoothGattCharacteristic(
            CHAR_COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(commandChar)

        gattServer?.addService(service)
        Log.i(TAG, "GATT Server configured with Proximity Service")
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun updateLockState() {
        val locked = keyguardManager?.isDeviceLocked == true
        if (locked != isDeviceLocked) {
            isDeviceLocked = locked
            Log.d(TAG, "Lock state updated: isDeviceLocked=$isDeviceLocked")
            notifyStatusChanged()
        }
    }

    private fun getStatusJson(): String {
        return "{\"locked\":$isDeviceLocked,\"on_body\":$isOnBody,\"battery\":$batteryPct,\"screen\":$isScreenOn}"
    }

    private fun notifyStatusChanged() {
        val gatt = gattServer ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CHAR_STATUS_UUID) ?: return

        val payload = getStatusJson().toByteArray(StandardCharsets.UTF_8)
        char.value = payload

        for (device in subscribedDevices) {
            gatt.notifyCharacteristicChanged(device, char, false)
        }
    }

    private fun handleCommand(value: ByteArray) {
        val cmd = if (value.size == 1) value[0].toInt() else {
            val str = String(value, StandardCharsets.UTF_8).trim()
            when (str) {
                "LOCK" -> CMD_LOCK
                "UNLOCK" -> CMD_UNLOCK
                "ALARM" -> CMD_ALARM
                "ALARM_STOP" -> CMD_ALARM_STOP
                else -> 0
            }
        }

        when (cmd) {
            CMD_LOCK -> onLaptopLocked()
            CMD_UNLOCK -> onLaptopUnlocked()
            CMD_ALARM -> onAlarmTriggered()
            CMD_ALARM_STOP -> onAlarmStopped()
            else -> Log.w(TAG, "Unknown command received: ${value.contentToString()}")
        }
    }

    private fun onLaptopLocked() {
        Log.i(TAG, "Action: LAPTOP LOCKED")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("💻 Laptop Locked")
            .setContentText("Locked via proximity away detection")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_NOTIFICATION_ID, notif)

        // Haptic: 2 short distinct taps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 120, 100, 150),
                    intArrayOf(0, 200, 0, 255),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 120, 100, 150), -1)
        }
    }

    private fun onLaptopUnlocked() {
        Log.i(TAG, "Action: LAPTOP UNLOCKED")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALERT_NOTIFICATION_ID)

        // Subtle confirmation tap
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(70, 120))
        }
    }

    private fun onAlarmTriggered() {
        Log.w(TAG, "Action: ANTI-THEFT ALARM TRIGGERED!")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setContentTitle("🚨 THEFT ALARM!")
            .setContentText("Laptop charger was unplugged while you were away!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
        nm.notify(ALARM_NOTIFICATION_ID, notif)

        // Urgent siren haptic pulses: 5 strong pulses
        val timings = longArrayOf(0, 300, 150, 300, 150, 300, 150, 300, 150, 500)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(timings, -1)
        }
    }

    private fun onAlarmStopped() {
        Log.i(TAG, "Action: ALARM STOPPED")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALARM_NOTIFICATION_ID)
        vibrator?.cancel()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == 34) { // TYPE_LOW_LATENCY_OFFBODY_DETECT
            val state = event.values[0]
            val newOnBody = state != 0f
            if (newOnBody != isOnBody) {
                isOnBody = newOnBody
                Log.i(TAG, "On-body sensor changed: isOnBody=$isOnBody")
                notifyStatusChanged()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val svcChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Proximity Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps telemetry and alerts running in background"
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Laptop Proximity Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Lock and proximity notifications"
                enableVibration(true)
            }

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM,
                "Anti-Theft Urgent Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alarm alerts"
                enableVibration(true)
            }

            nm.createNotificationChannel(svcChannel)
            nm.createNotificationChannel(alertChannel)
            nm.createNotificationChannel(alarmChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("Watch Proximity Active")
            .setContentText("Connected & broadcasting telemetry")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateLockState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        sensorManager?.unregisterListener(this)
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        }
    }
}
