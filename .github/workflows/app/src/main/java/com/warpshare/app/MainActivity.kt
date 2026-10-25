package com.warpshare.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ipInput: EditText
    private lateinit var ipBanner: TextView
    private val serviceHelper = RadioWakeService()
    private var isReceiving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Check Android 11-16 "All Files Access" so /Download/ never gives EACCES Permission Denied!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Turn ON 'Allow access to manage all files' for WarpShare!", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        // 2. Request Bluetooth & Nearby Wi-Fi Permissions
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 100)

        try {
            val svcIntent = Intent(this, RadioWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("warp_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "⚡ WARPSHARE v2.1 (ZERO-COPY)"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        }

        ipBanner = TextView(this).apply {
            text = "This Device IP: ${getRealHotspotOrWifiIp()} (Tap to refresh)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(0, 16, 0, 24)
            setOnClickListener {
                text = "This Device IP: ${getRealHotspotOrWifiIp()} (Tap to refresh)"
            }
        }

        ipInput = EditText(this).apply {
            hint = "Type THE OTHER DEVICE'S IP here"
            setText(prefs.getString("saved_ip", ""))
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        val btnAutoDiscover = Button(this).apply {
            text = "🔍 1. Wake Tablet (BLE) & Auto-Find IP"
            setOnClickListener {
                ipBanner.text = "This Device IP: ${getRealHotspotOrWifiIp()}"
                statusText.text = "Sending BLE Wake Pulse & searching network..."
                serviceHelper.sendBleWakePulse(this@MainActivity)
                serviceHelper.broadcastFindTablet()
            }
        }

        val btnReceiveMode = Button(this).apply {
            text = "📥 TABLET MODE: Start Always-On Receiver"
            setOnClickListener {
                ipBanner.text = "This Device IP: ${getRealHotspotOrWifiIp()}"
                startTabletReceiver()
            }
        }

        val btnPickAndSend = Button(this).apply {
            text = "🚀 PHONE MODE: Pick File & Blast (8 Streams)"
            setOnClickListener {
                prefs.edit().putString("saved_ip", ipInput.text.toString().trim()).apply()
                pickFile()
            }
        }

        speedText = TextView(this).apply {
            text = "0.0 MB/s"
            textSize = 36f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4ADE80"))
            setPadding(0, 32, 0, 16)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        statusText = TextView(this).apply {
            text = "IMPORTANT: Type the TABLET's IP into the PHONE's box (don't type the Phone's own IP!)."
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 24, 0, 0)
        }

        layout.addView(title)
        layout.addView(ipBanner)
        layout.addView(ipInput)
        layout.addView(btnAutoDiscover)
        layout.addView(btnReceiveMode)
        layout.addView(btnPickAndSend)
        layout.addView(speedText)
        layout.addView(progressBar)
        layout.addView(statusText)
        setContentView(layout)

        RadioWakeService.onStatusUpdate = { msg ->
            runOnUiThread { statusText.text = msg }
        }
        RadioWakeService.onTabletIpFound = { foundIp ->
            val myIp = getRealHotspotOrWifiIp()
            if (foundIp.isNotEmpty() && foundIp != myIp) {
                runOnUiThread {
                    ipInput.setText(foundIp)
                    prefs.edit().putString("saved_ip", foundIp).apply()
                    statusText.text = "✅ Auto-Found Tablet at $foundIp!"
                }
            }
        }

        if (intent?.action == Intent.ACTION_SEND) {
            val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) {
                serviceHelper.sendBleWakePulse(this)
                handleSendUri(sharedUri)
            }
        }
    }

    private fun startTabletReceiver() {
        if (isReceiving) return
        isReceiving = true
        val myIp = getRealHotspotOrWifiIp()
        statusText.text = "🟢 Receiver Active! Type '$myIp' into your Phone and tap Pick File."
        scope.launch {
            while (isActive) {
                try {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val savedFile = WarpEngine.receiveFile(downloads) { name, speed, pct ->
                        runOnUiThread {
                            speedText.text = String.format("%.1f MB/s", speed)
                            progressBar.progress = pct
                            statusText.text = "Receiving $name ($pct%)"
                        }
                    }
                    statusText.text = "✅ Saved to Downloads: ${savedFile.name}\nReady for next file!"
                } catch (e: Exception) {
                    statusText.text = "❌ Receiver error: ${e.message}"
                    delay(1500)
                }
            }
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 200)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) {
            data?.data?.let { handleSendUri(it) }
        }
    }

    private fun handleSendUri(uri: Uri) {
        val targetIp = ipInput.text.toString().trim()
        val myIp = getRealHotspotOrWifiIp()
        if (targetIp.isEmpty()) {
            statusText.text = "⚠️ Enter your Tablet's IP first!"
            return
        }
        if (targetIp == myIp) {
            statusText.text = "⚠️ You typed THIS device's own IP ($myIp)! Look at your Tablet's screen and type the Tablet's IP here."
            return
        }
        getSharedPreferences("warp_prefs", Context.MODE_PRIVATE)
            .edit().putString("saved_ip", targetIp).apply()

        scope.launch {
            try {
                val (fileName, fileSize) = getFileMeta(uri)
                statusText.text = "Connecting to $targetIp for $fileName..."
                WarpEngine.sendUriDirectly(contentResolver, targetIp, uri, fileName, fileSize) { speed, pct ->
                    runOnUiThread {
                        speedText.text = String.format("%.1f MB/s", speed)
                        progressBar.progress = pct
                        statusText.text = "Blasting $fileName ($pct%)"
                    }
                }
                statusText.text = "🚀 Transfer Complete! Sent $fileName"
            } catch (e: Exception) {
                statusText.text = "❌ Send error: ${e.message}"
            }
        }
    }

    private fun getFileMeta(uri: Uri): Pair<String, Long> {
        var name = "warpshare_${System.currentTimeMillis()}"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        if (size <= 0L) {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                size = pfd.statSize
            }
        }
        return Pair(name, size)
    }

    /**
     * Accurately finds Wi-Fi OR Mobile Hotspot IP (ignores cellular mobile data IPs like 192.0.0.4)
     */
    private fun getRealHotspotOrWifiIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallbackIp = ""
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                // Prioritize Wi-Fi and Hotspot interfaces (wlan0, swlan0, ap0, rndis0)
                val isWifiOrHotspot = name.contains("wlan") || name.contains("ap") || name.contains("swlan")
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.0.0.")) continue // Ignore cellular CLAT IP!
                        if (isWifiOrHotspot) return ip
                        fallbackIp = ip
                    }
                }
            }
            if (fallbackIp.isNotEmpty()) return fallbackIp
        } catch (_: Exception) {}
        return "Turn on Wi-Fi or Hotspot"
    }
}
