package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoverViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application.applicationContext)

    // Flow states from BLE Manager
    val connectionState = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices
    val temperature = bleManager.temperature
    val humidity = bleManager.humidity
    val lastSentBytes = bleManager.lastSentBytes
    val isSimulationMode = bleManager.isSimulationMode

    // Keep track of current servo angles locally to reflect on sliders
    private val _servoAngles = MutableStateFlow(List(6) { 90 })
    val servoAngles: StateFlow<List<Int>> = _servoAngles.asStateFlow()

    // Current joystick visual values for rendering
    private val _joystickPosition = MutableStateFlow(Pair(0f, 0f)) // Offset from center in pixels/dp
    val joystickPosition: StateFlow<Pair<Float, Float>> = _joystickPosition.asStateFlow()

    // Real-time joystick raw sent values (0-255)
    private val _joystickRaw = MutableStateFlow(Pair(127, 127))
    val joystickRaw: StateFlow<Pair<Int, Int>> = _joystickRaw.asStateFlow()

    // List of scanned devices is handled by BLE Manager

    fun isBleSupported(): Boolean = bleManager.isBleSupported()
    fun isBluetoothEnabled(): Boolean = bleManager.isBluetoothEnabled()

    fun toggleSimulationMode(enabled: Boolean) {
        bleManager.setSimulationMode(enabled)
    }

    fun startScanning() {
        bleManager.startScanning()
    }

    fun stopScanning() {
        bleManager.stopScanning()
    }

    fun connectDevice(address: String) {
        bleManager.connect(address)
    }

    fun disconnectDevice() {
        bleManager.disconnect()
    }

    fun updateJoystickState(xOffsetPercent: Float, yOffsetPercent: Float) {
        // xOffsetPercent and yOffsetPercent are in range [-1.0f, 1.0f]
        // Map to 0-255 for L298N motor driver
        // Joystick X-Axis (0-255)
        // Joystick Y-Axis (0-255)
        val rawX = ((xOffsetPercent + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
        // Note: For Y-axis, dragging up usually means positive movement but in screen space it is negative.
        // We invert Y-axis so dragging up increases speed (Y closer to 255 or 0 depending on firmware,
        // let's map Y-offset [-1, 1] -> Y raw [0, 255] where -1 (up) becomes 255 (full forward) and 1 (down) becomes 0 (full reverse).
        val rawY = (((-yOffsetPercent) + 1.0f) * 127.5f).toInt().coerceIn(0, 255)

        _joystickRaw.value = Pair(rawX, rawY)
        bleManager.updateJoystick(rawX, rawY)
    }

    fun resetJoystick() {
        _joystickRaw.value = Pair(127, 127)
        bleManager.updateJoystick(127, 127)
    }

    fun updateServoAngle(servoIndex: Int, angle: Int) {
        if (servoIndex in 0..5) {
            val current = _servoAngles.value.toMutableList()
            current[servoIndex] = angle
            _servoAngles.value = current
            bleManager.updateServo(servoIndex, angle)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
