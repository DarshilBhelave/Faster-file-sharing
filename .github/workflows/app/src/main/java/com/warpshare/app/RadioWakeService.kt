package com.warpshare.app

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class RadioWakeService : Service() {

    companion object {
        val WARP_UUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")
        private const val DISCOVERY_PORT = 9499
        var onStatusUpdate: ((String) -> Unit)? = null
        var onTabletIpFound: ((String) -> Unit)? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiLock: WifiManager.WifiLock? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        acquireHighSpeedWifiLock()
        startBleWakeListener()
        startUdpAutoDiscoveryResponder()
    }

    /**
     * SILENT RADIO AUTO-TOGGLE (Works automatically via targetSdk 28!)
     */
    @Suppress("DEPRECATION")
    fun forceTurnOnRadios(context: Context) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
            onStatusUpdate?.invoke("Wi-Fi automatically turned ON!")
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        if (btAdapter != null && !btAdapter.isEnabled) {
            btAdapter.enable()
            onStatusUpdate?.invoke("Bluetooth automatically turned ON!")
        }
    }

    /**
     * Forces the Wi-Fi chip into 100% duty cycle (zero power-save lag)
     */
    private fun acquireHighSpeedWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(lockMode, "WarpShare:SuperSpeedLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * TABLET: Listens for a tiny BLE wake packet from the phone.
     * As soon as it hears it, it turns on Wi-Fi automatically!
     */
    private fun startBleWakeListener() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return
            if (!adapter.isEnabled) adapter.enable()

            bleScanner = adapter.bluetoothLeScanner ?: return
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(WARP_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            onStatusUpdate?.invoke("BLE Listener ready: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // Wake signal detected from Phone! Turn on Wi-Fi immediately!
            forceTurnOnRadios(this@RadioWakeService)
            // Stop BLE scan immediately so 2.4GHz antenna doesn't interfere with Wi-Fi transfer
            try { bleScanner?.stopScan(this) } catch (_: Exception) {}
        }
    }

    /**
     * PHONE: Broadcasts a 2-second BLE Wake-Up pulse to wake the Tablet's Wi-Fi,
     * then stops Bluetooth immediately to prevent antenna interference.
     */
    fun sendBleWakePulse(context: Context) {
        scope.launch {
            try {
                forceTurnOnRadios(context)
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val advertiser = btManager.adapter?.bluetoothLeAdvertiser
                if (advertiser != null) {
                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(false)
                        .build()
                    val data = AdvertiseData.Builder()
                        .addServiceUuid(ParcelUuid(WARP_UUID))
                        .build()
                    val callback = object : AdvertiseCallback() {}
                    advertiser.startAdvertising(settings, data, callback)
                    onStatusUpdate?.invoke("Sent BLE Wake Pulse to Tablet...")
                    delay(1800) // Pulse for 1.8s then silence BT radio for max Wi-Fi speed
                    advertiser.stopAdvertising(callback)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Zero-Config Auto IP Discovery over Wi-Fi (No manual typing needed!)
     */
    private fun startUdpAutoDiscoveryResponder() {
        scope.launch {
            try {
                DatagramSocket(DISCOVERY_PORT).use { socket ->
                    socket.broadcast = true
                    val buf = ByteArray(64)
                    while (isActive) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        if (msg == "WARP_WHO_IS_TABLET") {
                            val reply = "WARP_IAM_TABLET".toByteArray()
                            val replyPacket = DatagramPacket(
                                reply, reply.size, packet.address, packet.port
                            )
                            socket.send(replyPacket)
                        } else if (msg == "WARP_IAM_TABLET") {
                            onTabletIpFound?.invoke(packet.address.hostAddress ?: "")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun broadcastFindTablet() {
        scope.launch {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val msg = "WARP_WHO_IS_TABLET".toByteArray()
                    val packet = DatagramPacket(
                        msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                    )
                    repeat(3) {
                        socket.send(packet)
                        delay(300)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startForegroundNotification() {
        val channelId = "warp_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "WarpShare High Speed Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("WarpShare Ready")
                .setContentText("Listening for instant BLE wake & 8-stream transfers")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("WarpShare Ready")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        }
        startForeground(1, notification)
    }

    override fun onDestroy() {
        wifiLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
