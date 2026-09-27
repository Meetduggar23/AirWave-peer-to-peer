package com.example.blechat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * Handles both BLE roles at once so two phones can chat with zero setup:
 *  - Peripheral role: advertises + runs a GATT server with a chat characteristic.
 *  - Central role: scans for peers advertising the chat service and connects.
 *
 * On a single link, the central sends via characteristic WRITE and the
 * peripheral replies via NOTIFY on the same characteristic.
 */
@SuppressLint("MissingPermission") // Permissions are requested in MainActivity
class BleChatManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(text: String)
        fun onPeerFound(name: String, address: String)
        fun onConnectionChanged(address: String, connected: Boolean)
        fun onMessageReceived(sender: String, text: String)
    }

    companion object {
        // Nordic UART-style UUIDs, used here as a generic chat service.
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAT_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_MTU = 517
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null

    private var serverChatChar: BluetoothGattCharacteristic? = null
    private var clientChatChar: BluetoothGattCharacteristic? = null

    private var deviceName: String = Build.MODEL ?: "Android"
    private var advertising = false
    private var scanning = false
    private var clientReady = false

    private val peripheralConnections = mutableSetOf<BluetoothDevice>()

    // ---------------- Public API ----------------

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun start(name: String) {
        deviceName = name.ifBlank { Build.MODEL ?: "Android" }
        try {
            adapter?.name = deviceName
        } catch (_: Exception) {
            // Some devices ignore renames; the advertised name still works.
        }
        startGattServer()
        startAdvertising()
        startScanning()
    }

    fun stop() {
        stopScanning()
        stopAdvertising()
        try {
            gattClient?.close()
        } catch (_: Exception) {
        }
        gattClient = null
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        peripheralConnections.clear()
        clientChatChar = null
        serverChatChar = null
        clientReady = false
        listener.onStatus("Stopped")
    }

    fun connect(address: String) {
        val device = adapter?.getRemoteDevice(address) ?: return
        try {
            gattClient?.close()
        } catch (_: Exception) {
        }
        clientReady = false
        stopScanning()
        listener.onStatus("Connecting to $address…")
        gattClient = device.connectGatt(context, false, gattClientCallback)
    }

    /**
     * Sends a chat message. Returns true if it was handed to the BLE stack,
     * false if there is no usable connection yet.
     */
    fun sendMessage(text: String): Boolean {
        val payload = "$deviceName|$text".toByteArray(Charsets.UTF_8)

        // Preferred path: we are the central, WRITE to the peer's server.
        val gatt = gattClient
        val clientChar = clientChatChar
        if (gatt != null && clientChar != null && clientReady) {
            writeCharacteristicCompat(gatt, clientChar, payload)
            return true
        }

        // Fallback path: we are the peripheral, NOTIFY connected centrals.
        val server = gattServer
        val serverChar = serverChatChar
        if (server != null && serverChar != null && peripheralConnections.isNotEmpty()) {
            for (d in peripheralConnections.toList()) {
                notifyCompat(server, d, serverChar, payload)
            }
            return true
        }
        return false
    }

    // ---------------- Peripheral: advertising + GATT server ----------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            listener.onStatus("Advertising as \"$deviceName\"…")
        }

        override fun onStartFailure(errorCode: Int) {
            listener.onStatus("Advertising failed (code $errorCode)")
        }
    }

    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            listener.onStatus("BLE advertising not supported on this device")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (advertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
        }
        advertising = false
    }

    private fun startGattServer() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val chatChar = BluetoothGattCharacteristic(
            CHAT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // CCCD is required so the peer can enable notifications.
        chatChar.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(chatChar)
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
        serverChatChar = chatChar
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                peripheralConnections.add(device)
                listener.onConnectionChanged(device.address, true)
                listener.onStatus("Peer connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peripheralConnections.remove(device)
                listener.onConnectionChanged(device.address, false)
                listener.onStatus("Peer disconnected: ${device.address}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAT_CHAR_UUID) {
                val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    value
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value
                }
                if (bytes != null) handleIncoming(bytes)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // Accept CCCD writes so the peer can enable notifications.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    // ---------------- Central: scanning + GATT client ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            listener.onPeerFound(name, device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onStatus("Scan failed (code $errorCode)")
        }
    }

    private fun startScanning() {
        scanner = adapter?.bluetoothLeScanner ?: run {
            listener.onStatus("BLE scanning not supported on this device")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        listener.onStatus("Scanning for peers…")
    }

    private fun stopScanning() {
        if (scanning) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
        }
        scanning = false
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onConnectionChanged(gatt.device.address, true)
                listener.onStatus("Connected, discovering services…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clientReady = false
                clientChatChar = null
                listener.onConnectionChanged(gatt.device.address, false)
                listener.onStatus("Disconnected")
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                if (gatt == gattClient) gattClient = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAT_CHAR_UUID) ?: run {
                listener.onStatus("Chat service not found on peer")
                return
            }
            clientChatChar = char
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(CCCD_UUID)?.let { cccd ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }
            gatt.requestMtu(MAX_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            clientReady = true
            listener.onStatus("Ready to chat (MTU $mtu)")
        }

        // API < 33
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleIncoming(bytes)
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }
    }

    // ---------------- Helpers ----------------

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun notifyCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, char, false, value)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, char, false)
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        val raw = bytes.toString(Charsets.UTF_8)
        val idx = raw.indexOf('|')
        val sender = if (idx >= 0) raw.substring(0, idx) else "Peer"
        val text = if (idx >= 0) raw.substring(idx + 1) else raw
        listener.onMessageReceived(sender, text)
    }
}
