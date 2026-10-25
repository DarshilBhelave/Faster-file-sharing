package com.warpshare.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

@SuppressLint("MissingPermission")
class RadioWakeService : Service() {

    companion object {
        private const val RADAR_PORT = 9499
        @Volatile var peerIp: String? = null
        var onPeerConnected: ((String) -> Unit)? = null
        var onStatusUpdate: ((String) -> Unit)? = null
        var onTabletIpFound: ((String) -> Unit)? = null

        fun getLocalHotspotIp(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                var fallback: String? = null
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    val isHotspotOrWifi = name.contains("wlan") || name.contains("ap") || name.contains("swlan") || name.contains("rndis")
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.0.0.")) continue
                            if (isHotspotOrWifi) return ip
                            if (fallback == null) fallback = ip
                        }
                    }
                }
                return fallback
            } catch (_: Exception) {}
            return null
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        acquireHighSpeedWifiLock()
        startHotspotRadarListener()
        startHotspotRadarBeacon()
    }

    private fun acquireHighSpeedWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "WarpShare:HotspotLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun startHotspotRadarListener() {
        scope.launch {
            while (isActive) {
                try {
                    DatagramSocket(RADAR_PORT).use { socket ->
                        socket.broadcast = true
                        val buf = ByteArray(64)
                        while (isActive) {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length)
                            val senderIp = packet.address.hostAddress ?: continue
                            val myIp = getLocalHotspotIp()

                            if (msg.startsWith("WARP_HELLO") && senderIp != myIp) {
                                peerIp = senderIp
                                onPeerConnected?.invoke(senderIp)
                                onTabletIpFound?.invoke(senderIp)

                                val reply = "WARP_HELLO".toByteArray()
                                socket.send(DatagramPacket(reply, reply.size, packet.address, RADAR_PORT))
                            }
                        }
                    }
                } catch (_: Exception) {
                    delay(1000)
                }
            }
        }
    }

    private fun startHotspotRadarBeacon() {
        scope.launch {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val msg = "WARP_HELLO".toByteArray()
                while (isActive) {
                    try {
                        val myIp = getLocalHotspotIp()
                        if (myIp != null) {
                            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), RADAR_PORT))
                            val parts = myIp.split(".")
                            if (parts.size == 4) {
                                val subnetBroadcast = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(subnetBroadcast), RADAR_PORT))
                                val gatewayIp = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                if (gatewayIp != myIp) {
                                    socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(gatewayIp), RADAR_PORT))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    delay(1200)
                }
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "warp_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "WarpShare Radar", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("WarpShare Hotspot Radar Active")
                .setContentText("Auto-detecting connected devices...")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("WarpShare").build()
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
