package com.warpshare.app

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.provider.Settings
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

@SuppressLint("MissingPermission")
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

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        acquireHighSpeedWifiLock()
        startBleWakeListener()
        startUdpAutoDiscoveryResponder()
    }

    fun ensureWifiReady(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                onStatusUpdate?.invoke("Wake signal received! Opening Wi-Fi panel...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(panelIntent)
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = true
                }
            }
        } catch (_: Exception) {}
    }

    private fun acquireHighSpeedWifiLock() {
        try {
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
        } catch (_: Exception) {}
    }

    private fun startBleWakeListener() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return
            if (!adapter.isEnabled) return

            bleScanner = adapter.bluetoothLeScanner ?: return
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(WARP_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            ensureWifiReady(this@RadioWakeService)
            try { bleScanner?.stopScan(this) } catch (_: Exception) {}
        }
    }

    fun sendBleWakePulse(context: Context) {
        scope.launch {
            try {
                ensureWifiReady(context)
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter ?: return@launch
                val advertiser = adapter.bluetoothLeAdvertiser ?: return@launch

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
                onStatusUpdate?.invoke("Sent BLE Wake Pulse! Optimizing for Wi-Fi...")
                delay(1500)
                try {
                    advertiser.stopAdvertising(callback)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

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
                        delay(250)
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
                .setContentTitle("WarpShare 8-Stream Active")
                .setContentText("High-Speed 5GHz Low-Latency Lock Enabled")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("WarpShare Active")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        wifiLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
