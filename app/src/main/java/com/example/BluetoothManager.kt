package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Robust Android Bluetooth Classic (RFCOMM SPP) Manager
 * Supports Host & Multi-client architecture (Host + up to 3 clients = 4 players)
 */
class GameBluetoothManager(private val context: Context, private val webView: WebView) {

    private val tag = "GameBluetooth"
    private val appUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID
    private val serviceName = "AmiralBatti30x30"

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var serverThread: ServerThread? = null
    private val connectedSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val clientStreams = ConcurrentHashMap<BluetoothSocket, OutputStream>()
    private val mainHandler = Handler(Looper.getMainLooper())

    var isHost: Boolean = false
    private var isReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let { dev ->
                        val devObj = JSONObject().apply {
                            put("name", dev.name ?: "Bilinmeyen Cihaz")
                            put("address", dev.address ?: "")
                        }
                        mainHandler.post {
                            webView.evaluateJavascript(
                                "window.onAndroidBluetoothDeviceDiscovered && window.onAndroidBluetoothDeviceDiscovered(${devObj});",
                                null
                            )
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    notifyStatus("Bluetooth taraması tamamlandı.", false)
                }
            }
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getPairedDevicesJson(): String {
        if (!isBluetoothSupported() || !isBluetoothEnabled()) return "[]"
        val array = JSONArray()
        try {
            bluetoothAdapter?.bondedDevices?.forEach { dev ->
                val obj = JSONObject().apply {
                    put("name", dev.name ?: "İsimsiz Cihaz")
                    put("address", dev.address ?: "")
                }
                array.put(obj)
            }
        } catch (e: Exception) {
            Log.e(tag, "Paired devices query failed", e)
        }
        return array.toString()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            notifyStatus("Bluetooth kapalı veya desteklenmiyor", false)
            return
        }
        try {
            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(bluetoothReceiver, filter)
                isReceiverRegistered = true
            }
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
            notifyStatus("Çevredeki cihazlar taranıyor...", false)
        } catch (e: Exception) {
            Log.e(tag, "Discovery error", e)
            notifyStatus("Tarama hatası: ${e.message}", false)
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            notifyStatus("Bluetooth kapalı veya desteklenmiyor. Lütfen Bluetooth'u açın.", false)
            return
        }
        isHost = true
        stopAll()

        serverThread = ServerThread()
        serverThread?.start()
        notifyStatus("Bluetooth Sunucusu hazır (Oda dinleniyor)", true)
    }

    @SuppressLint("MissingPermission")
    fun scanAndConnect(targetDeviceAddress: String? = null) {
        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            notifyStatus("Bluetooth kapalı veya desteklenmiyor", false)
            return
        }

        if (!targetDeviceAddress.isNullOrEmpty()) {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(targetDeviceAddress)
                if (device != null) {
                    connectToDevice(device)
                    return
                }
            } catch (e: Exception) {
                notifyStatus("Geçersiz cihaz adresi: ${e.message}", false)
                return
            }
        }

        // Get paired devices
        val pairedDevices = bluetoothAdapter?.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            notifyStatus("Eşleşmiş cihaz bulunamadı. Lütfen önce Bluetooth ayarlarından eşleştirin veya tarama yapın.", false)
        } else {
            val firstDevice = pairedDevices.first()
            connectToDevice(firstDevice)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            try {
                cancelDiscovery()
                val devName = try { device.name ?: device.address } catch (_: Exception) { device.address }
                notifyStatus("$devName ile bağlantı kuruluyor...", false)
                
                val socket = try {
                    device.createRfcommSocketToServiceRecord(appUuid)
                } catch (e: Exception) {
                    // Fallback to hidden method if standard fails
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                }

                socket.connect()
                handleConnectedSocket(socket)
                notifyStatus("$devName bağlandı!", true)
            } catch (e: Exception) {
                Log.e(tag, "Bağlantı hatası", e)
                notifyStatus("Bağlantı başarısız: ${e.localizedMessage ?: "Cihaz yanıt vermedi"}", false)
            }
        }.start()
    }

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        connectedSockets.add(socket)
        val outStream = socket.outputStream
        clientStreams[socket] = outStream

        // Start listening loop
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (socket.isConnected) {
                    val line = reader.readLine() ?: break
                    dispatchMessageToWebView(line)
                    // If host, forward to other connected clients (broadcast)
                    if (isHost) {
                        broadcastExcept(line, socket)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Socket okuma koptu: ${e.message}")
            } finally {
                connectedSockets.remove(socket)
                clientStreams.remove(socket)
                try { socket.close() } catch (_: Exception) {}
                notifyStatus("Bir oyuncunun bağlantısı kesildi", connectedSockets.isNotEmpty())
            }
        }.start()
    }

    fun broadcastMessage(jsonMessage: String) {
        val payload = (jsonMessage.trim() + "\n").toByteArray()
        clientStreams.values.forEach { out ->
            try {
                out.write(payload)
                out.flush()
            } catch (e: Exception) {
                Log.e(tag, "Mesaj gönderme hatası", e)
            }
        }
    }

    private fun broadcastExcept(jsonMessage: String, excludeSocket: BluetoothSocket) {
        val payload = (jsonMessage.trim() + "\n").toByteArray()
        clientStreams.forEach { (socket, out) ->
            if (socket != excludeSocket) {
                try {
                    out.write(payload)
                    out.flush()
                } catch (e: Exception) {
                    Log.e(tag, "Broadcast hatası", e)
                }
            }
        }
    }

    private fun dispatchMessageToWebView(jsonString: String) {
        mainHandler.post {
            val escaped = JSONObject.quote(jsonString)
            webView.evaluateJavascript("window.onAndroidBluetoothMessage && window.onAndroidBluetoothMessage($escaped);", null)
        }
    }

    private fun notifyStatus(status: String, isConnected: Boolean) {
        mainHandler.post {
            val escapedStatus = JSONObject.quote(status)
            webView.evaluateJavascript("window.onAndroidBluetoothStatus && window.onAndroidBluetoothStatus($escapedStatus, $isConnected);", null)
        }
    }

    fun stopAll() {
        cancelDiscovery()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        try { serverThread?.cancel() } catch (_: Exception) {}
        serverThread = null
        connectedSockets.forEach { try { it.close() } catch (_: Exception) {} }
        connectedSockets.clear()
        clientStreams.clear()
    }

    private inner class ServerThread : Thread() {
        @SuppressLint("MissingPermission")
        private val serverSocket: BluetoothServerSocket? = try {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(serviceName, appUuid)
        } catch (e: Exception) {
            Log.e(tag, "Server listen hatası", e)
            null
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop && serverSocket != null) {
                try {
                    val socket = serverSocket.accept()
                    if (socket != null) {
                        handleConnectedSocket(socket)
                        notifyStatus("Yeni bir oyuncu odaya bağlandı! (${connectedSockets.size + 1} Oyuncu)", true)
                    }
                } catch (e: Exception) {
                    shouldLoop = false
                }
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }
}
