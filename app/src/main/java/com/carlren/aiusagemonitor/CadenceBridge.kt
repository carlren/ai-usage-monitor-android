package com.carlren.aiusagemonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.Properties
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import org.json.JSONObject

data class CadenceBridgeState(
    val advertising: Boolean = false,
    val relayConnected: Boolean = false,
    val zwiftConnected: Boolean = false,
    val cadenceSpm: Int = 0,
    val error: String? = null,
)

class CadenceBridge(
    context: Context,
    private val onState: (CadenceBridgeState) -> Unit,
) {
    companion object {
        private const val TAG = "CadenceBridge"
        private const val DEVICE_NAME = "RUN CADENCE BRIDGE"
        private const val STALE_AFTER_MS = 3_500L

        private val RSC_SERVICE_UUID: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        private val RSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        private val RSC_FEATURE_UUID: UUID = UUID.fromString("00002a54-0000-1000-8000-00805f9b34fb")
        private val SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002a5d-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val subscribers = CopyOnWriteArraySet<BluetoothDevice>()
    private var state = CadenceBridgeState()
    private var server: BluetoothGattServer? = null
    private var measurement: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var pollThread: Thread? = null
    private var running = false
    private var lastRelayPacketAt = 0L
    private var originalAdapterName: String? = null
    private val apiConfig: CadenceApiConfig? by lazy(::loadApiConfig)

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastRelayPacketAt > STALE_AFTER_MS) {
                updateState(state.copy(relayConnected = false, cadenceSpm = 0))
            }
            notifyCadence(state.cadenceSpm)
            handler.postDelayed(this, 1_000L)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start() {
        if (running) return
        running = true
        startVpsPolling()
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            updateState(state.copy(error = "Bluetooth adapter unavailable"))
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            bluetoothAdapter.enable()
            handler.postDelayed(::startBluetoothServer, 1_800L)
        } else {
            startBluetoothServer()
        }
        handler.post(heartbeat)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        if (!running) return
        val bluetoothAdapter = adapter ?: return
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            updateState(state.copy(error = "BLE advertising is unavailable"))
            return
        }

        originalAdapterName = bluetoothAdapter.name
        bluetoothAdapter.name = DEVICE_NAME
        server = manager.openGattServer(appContext, serverCallback)
        if (server == null) {
            updateState(state.copy(error = "Could not open BLE GATT server"))
            return
        }

        val service = BluetoothGattService(RSC_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        measurement = BluetoothGattCharacteristic(
            RSC_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            value = encodeMeasurement(0)
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        service.addCharacteristic(measurement)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RSC_FEATURE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply { value = byteArrayOf(0, 0) },
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                SENSOR_LOCATION_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply { value = byteArrayOf(1) },
        )
        server?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (!running) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "RSC advertising started")
                updateState(state.copy(advertising = true, error = null))
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "RSC advertising failed: $errorCode")
                updateState(state.copy(error = "BLE advertising failed ($errorCode)"))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build(),
            AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(RSC_SERVICE_UUID))
                .setIncludeTxPowerLevel(true)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build(),
            callback,
        )
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS && service?.uuid == RSC_SERVICE_UUID) {
                startAdvertising()
            } else {
                updateState(state.copy(error = "RSC service registration failed ($status)"))
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device != null) {
                subscribers.remove(device)
                updateState(state.copy(zwiftConnected = subscribers.isNotEmpty()))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: byteArrayOf()
            val response = if (offset in 0..value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val enabled = device in subscribers
            val value = if (enabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (enable) subscribers.add(device) else subscribers.remove(device)
            updateState(state.copy(zwiftConnected = subscribers.isNotEmpty()))
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (enable) notifyCadence(state.cadenceSpm)
        }
    }

    private fun startVpsPolling() {
        pollThread = thread(name = "cadence-vps-poll", isDaemon = true) {
            var failures = 0
            while (running) {
                try {
                    val current = apiRequest("GET", "/current")
                    val fresh = current.optBoolean("cadence_fresh", false)
                    val cadence = if (fresh) {
                        current.optDouble("cadence_spm", 0.0).toInt().coerceIn(0, 255)
                    } else {
                        0
                    }
                    if (fresh) lastRelayPacketAt = SystemClock.elapsedRealtime()
                    handler.post {
                        updateState(
                            state.copy(
                                relayConnected = fresh,
                                cadenceSpm = cadence,
                                error = null,
                            ),
                        )
                        notifyCadence(cadence)
                    }
                    apiRequest(
                        "PUT",
                        "/client",
                        JSONObject().put("zwift_connected", state.zwiftConnected).toString(),
                    )
                    failures = 0
                } catch (failure: Exception) {
                    failures += 1
                    if (failures >= 2) {
                        Log.w(TAG, "Cadence API polling failed", failure)
                        handler.post {
                            updateState(
                                state.copy(
                                    relayConnected = false,
                                    cadenceSpm = 0,
                                    error = "Cadence API unavailable",
                                ),
                            )
                        }
                    }
                }
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun apiRequest(method: String, path: String, body: String? = null): JSONObject {
        val config = requireNotNull(apiConfig) { "Cadence API configuration is missing" }
        val connection = (URL(config.url.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = connection.responseCode
        val response = if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        connection.disconnect()
        check(code in 200..299) { "Cadence API HTTP $code" }
        return JSONObject(response)
    }

    private fun loadApiConfig(): CadenceApiConfig? {
        val file = appContext.filesDir.resolve("cadence.properties")
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties().apply { file.inputStream().use(::load) }
            val url = properties.getProperty("CADENCE_API_URL")?.trim().orEmpty()
            val token = properties.getProperty("CADENCE_API_TOKEN")?.trim().orEmpty()
            require(url.startsWith("https://")) { "Cadence API must use HTTPS" }
            require(token.isNotBlank()) { "Cadence API token is empty" }
            CadenceApiConfig(url, token)
        }.getOrElse { error ->
            Log.e(TAG, "Invalid private cadence configuration", error)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyCadence(cadence: Int) {
        val characteristic = measurement ?: return
        characteristic.value = encodeMeasurement(cadence)
        subscribers.forEach { device ->
            server?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun encodeMeasurement(cadence: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putShort(0)
            .put(cadence.coerceIn(0, 255).toByte())
            .array()

    private fun updateState(next: CadenceBridgeState) {
        state = next
        onState(next)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        handler.removeCallbacks(heartbeat)
        pollThread?.interrupt()
        pollThread = null
        advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertiseCallback = null
        server?.clearServices()
        server?.close()
        server = null
        subscribers.clear()
        originalAdapterName?.let { adapter?.name = it }
        originalAdapterName = null
        updateState(CadenceBridgeState())
    }
}

private data class CadenceApiConfig(val url: String, val token: String)
