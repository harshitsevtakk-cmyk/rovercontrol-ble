package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    private val TAG = "BleManager"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // UI States
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _temperature = MutableStateFlow(0)
    val temperature: StateFlow<Int> = _temperature.asStateFlow()

    private val _humidity = MutableStateFlow(0)
    val humidity: StateFlow<Int> = _humidity.asStateFlow()

    private val _lastSentBytes = MutableStateFlow<ByteArray?>(null)
    val lastSentBytes: StateFlow<ByteArray?> = _lastSentBytes.asStateFlow()

    private val _isSimulationMode = MutableStateFlow(true) // Enabled by default for easy demo/testing
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    // Active BLE connections and scopes
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scanJob: Job? = null
    private var txTickerJob: Job? = null
    private var simulationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Standard UART/Serial UUIDs
    private val UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val UART_RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write
    private val UART_TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify

    private val SIMPLE_SERIAL_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val SIMPLE_SERIAL_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    // Client Characteristic Configuration Descriptor (CCCD) for enabling notifications
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Current target state to transmit
    private var joystickX = 127
    private var joystickY = 127
    private val servoAngles = IntArray(6) { 90 } // Default center 90 degrees

    init {
        // Automatically start simulation tick if in simulation mode
        if (_isSimulationMode.value) {
            startSimulationMode()
        }
    }

    fun setSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        if (enabled) {
            disconnect()
            startSimulationMode()
        } else {
            stopSimulation()
            if (_connectionState.value == BleConnectionState.CONNECTED) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    // Update target parameters (called by ViewModel)
    fun updateJoystick(x: Int, y: Int) {
        joystickX = x.coerceIn(0, 255)
        joystickY = y.coerceIn(0, 255)
    }

    fun updateServo(index: Int, angle: Int) {
        if (index in 0..5) {
            servoAngles[index] = angle.coerceIn(0, 180)
        }
    }

    fun isBleSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled ?: false

    // Start Scanning for Rover devices
    fun startScanning() {
        if (_isSimulationMode.value) {
            // Simulated Scanning
            _connectionState.value = BleConnectionState.SCANNING
            scope.launch {
                delay(1500)
                val mockDevices = listOf(
                    ScannedDevice("ESP32_ROVER_ALPHA", "24:0A:C4:82:11:22", -65),
                    ScannedDevice("ROVER_CONTROL_WROOM", "30:AE:A4:07:0F:0E", -72),
                    ScannedDevice("L298N_MOTOR_SERVO", "C4:4F:33:15:AA:BB", -85)
                )
                _scannedDevices.value = mockDevices
            }
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        _scannedDevices.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        // Stop active scan if running
        scanJob?.cancel()

        scanJob = scope.launch {
            try {
                scanner.startScan(scanCallback)
                delay(8000) // Scan for 8 seconds
                scanner.stopScan(scanCallback)
                if (_connectionState.value == BleConnectionState.SCANNING) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during BLE scanning: ${e.message}")
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    fun stopScanning() {
        if (_isSimulationMode.value) {
            if (_connectionState.value == BleConnectionState.SCANNING) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Stop scan error: ${e.message}")
        }
        scanJob?.cancel()
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val deviceName = device.name ?: "Unknown Device"
            val deviceAddress = device.address
            val rssi = result.rssi

            val currentList = _scannedDevices.value
            if (currentList.none { it.address == deviceAddress }) {
                _scannedDevices.value = currentList + ScannedDevice(deviceName, deviceAddress, rssi)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            for (result in results) {
                val device = result.device
                val deviceName = device.name ?: "Unknown Device"
                val deviceAddress = device.address
                val rssi = result.rssi

                val currentList = _scannedDevices.value
                if (currentList.none { it.address == deviceAddress }) {
                    _scannedDevices.value = currentList + ScannedDevice(deviceName, deviceAddress, rssi)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Scan Failed with error: $errorCode")
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    // Connect to Selected Device
    fun connect(address: String) {
        stopScanning()

        if (_isSimulationMode.value) {
            _connectionState.value = BleConnectionState.CONNECTING
            scope.launch {
                delay(1200)
                _connectionState.value = BleConnectionState.CONNECTED
                _temperature.value = 24
                _humidity.value = 52
                startSimulationTelemetryAndTx()
            }
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = BleConnectionState.CONNECTING

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        if (_isSimulationMode.value) {
            stopSimulation()
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }

        txTickerJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = BleConnectionState.CONNECTED
                    Log.i(TAG, "GATT Connected. Discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cleanupConnection()
                }
            } else {
                Log.e(TAG, "GATT Error: status $status")
                cleanupConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT Services Discovered.")
                setupUartOrSerial(gatt)
            } else {
                Log.e(TAG, "Services Discovery Failed: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            handleIncomingData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            handleIncomingData(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status")
            }
        }
    }

    private fun setupUartOrSerial(gatt: BluetoothGatt) {
        // Try UART Service first
        var service = gatt.getService(UART_SERVICE_UUID)
        if (service != null) {
            val rxChar = service.getCharacteristic(UART_RX_CHAR_UUID)
            val txChar = service.getCharacteristic(UART_TX_CHAR_UUID)
            if (rxChar != null) {
                writeCharacteristic = rxChar
                Log.i(TAG, "UART Rx (Write) Characteristic found.")
            }
            if (txChar != null) {
                enableNotifications(gatt, txChar)
            }
        } else {
            // Try Simple Serial
            service = gatt.getService(SIMPLE_SERIAL_SERVICE_UUID)
            if (service != null) {
                val serialChar = service.getCharacteristic(SIMPLE_SERIAL_CHAR_UUID)
                if (serialChar != null) {
                    writeCharacteristic = serialChar
                    Log.i(TAG, "Simple Serial Characteristic found (Write/Notify).")
                    enableNotifications(gatt, serialChar)
                }
            } else {
                // Fallback: search ALL services for a writable & notifying characteristic
                Log.i(TAG, "Predefined service not found. Scanning services/characteristics dynamically...")
                var writeCharFound = false
                var notifyCharFound = false
                for (srv in gatt.services) {
                    for (charac in srv.characteristics) {
                        val properties = charac.properties
                        if (!writeCharFound && (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {
                            writeCharacteristic = charac
                            writeCharFound = true
                            Log.i(TAG, "Found dynamic Write Characteristic: ${charac.uuid} in service ${srv.uuid}")
                        }
                        if (!notifyCharFound && (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)) {
                            enableNotifications(gatt, charac)
                            notifyCharFound = true
                            Log.i(TAG, "Found dynamic Notify Characteristic: ${charac.uuid} in service ${srv.uuid}")
                        }
                    }
                }
            }
        }

        // Start High-Performance Transmit Ticker (25ms intervals)
        startTransmitTicker()
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            Log.i(TAG, "Notifications enabled on characteristic: ${characteristic.uuid}")
        }
    }

    private fun handleIncomingData(data: ByteArray?) {
        if (data == null || data.size < 2) return
        // 2 Bytes incoming data: DHT11 temperature (Byte 0), DHT11 humidity (Byte 1)
        val temp = data[0].toInt() and 0xFF
        val hum = data[1].toInt() and 0xFF

        _temperature.value = temp
        _humidity.value = hum
        Log.i(TAG, "Received Telemetry -> Temp: $temp°C, Humidity: $hum%")
    }

    private fun startTransmitTicker() {
        txTickerJob?.cancel()
        txTickerJob = scope.launch {
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED) {
                transmitControlPacket()
                delay(25) // 25ms throttle
            }
        }
    }

    // Packet Builder & Writer
    private fun transmitControlPacket() {
        val gatt = bluetoothGatt ?: return
        val charac = writeCharacteristic ?: return

        // 9 Bytes Packet
        // Byte 0: Sync Header (0xAA)
        // Byte 1: Joystick X (0-255)
        // Byte 2: Joystick Y (0-255)
        // Bytes 3-8: Servo 1-6 angles (0-180)
        val packet = ByteArray(9)
        packet[0] = 0xAA.toByte()
        packet[1] = joystickX.toByte()
        packet[2] = joystickY.toByte()
        for (i in 0..5) {
            packet[3 + i] = servoAngles[i].toByte()
        }

        charac.value = packet
        // Write without response for maximum performance / low latency
        val writeType = if (charac.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        charac.writeType = writeType
        gatt.writeCharacteristic(charac)

        _lastSentBytes.value = packet
    }

    private fun cleanupConnection() {
        txTickerJob?.cancel()
        _connectionState.value = BleConnectionState.DISCONNECTED
        writeCharacteristic = null
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    // SIMULATION MODE IMPLEMENTATIONS
    private fun startSimulationMode() {
        stopSimulation()
        _temperature.value = 0
        _humidity.value = 0
    }

    private fun startSimulationTelemetryAndTx() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            // Heartbeat/Transmit Ticker at 25ms in simulation mode to show real-time packet state in GUI
            val txJob = launch {
                while (isActive) {
                    val packet = ByteArray(9)
                    packet[0] = 0xAA.toByte()
                    packet[1] = joystickX.toByte()
                    packet[2] = joystickY.toByte()
                    for (i in 0..5) {
                        packet[3 + i] = servoAngles[i].toByte()
                    }
                    _lastSentBytes.value = packet
                    delay(25)
                }
            }

            // DHT11 Readout updater every 2 seconds
            val telemetryJob = launch {
                var baseTemp = 24
                var baseHum = 55
                while (isActive) {
                    // Small fluctuation
                    val randTemp = baseTemp + (-1..1).random()
                    val randHum = baseHum + (-2..2).random()
                    _temperature.value = randTemp.coerceIn(15, 45)
                    _humidity.value = randHum.coerceIn(20, 95)
                    delay(2000)
                }
            }

            // Wait until cancelled
            try {
                joinAll(txJob, telemetryJob)
            } finally {
                _lastSentBytes.value = null
            }
        }
    }

    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        _lastSentBytes.value = null
    }
}
