package com.example

import android.webkit.JavascriptInterface

/**
 * JavaScript Interface exposed to WebView as `window.AndroidBluetooth`
 */
class AndroidBluetoothBridge(private val bluetoothManager: GameBluetoothManager) {

    @JavascriptInterface
    fun isSupported(): Boolean {
        return bluetoothManager.isBluetoothSupported()
    }

    @JavascriptInterface
    fun isEnabled(): Boolean {
        return bluetoothManager.isBluetoothEnabled()
    }

    @JavascriptInterface
    fun getPairedDevices(): String {
        return bluetoothManager.getPairedDevicesJson()
    }

    @JavascriptInterface
    fun startDiscovery() {
        bluetoothManager.startDiscovery()
    }

    @JavascriptInterface
    fun cancelDiscovery() {
        bluetoothManager.cancelDiscovery()
    }

    @JavascriptInterface
    fun startServer() {
        bluetoothManager.startServer()
    }

    @JavascriptInterface
    fun scanDevices() {
        bluetoothManager.scanAndConnect()
    }

    @JavascriptInterface
    fun connectToAddress(address: String) {
        bluetoothManager.scanAndConnect(address)
    }

    @JavascriptInterface
    fun sendMessage(jsonMessage: String) {
        bluetoothManager.broadcastMessage(jsonMessage)
    }

    @JavascriptInterface
    fun disconnect() {
        bluetoothManager.stopAll()
    }
}

