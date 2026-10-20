package com.warpshare.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Gravity
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ipInput: EditText
    private val serviceHelper = RadioWakeService()
    private var isReceiving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Android 12 - 16 Nearby & Bluetooth permissions
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

        // Start Background Service safely
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
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "⚡ WARPSHARE v2.0 (8-STREAM)"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        }

        val ipBanner = TextView(this).apply {
            text = "This Device IP: ${getLocalIpAddress()}"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(0, 16, 0, 24)
        }

        ipInput = EditText(this).apply {
            hint = "Enter or Auto-Find Tablet IP"
            setText(prefs.getString("saved_ip", ""))
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        val btnAutoDiscover = Button(this).apply {
            text = "🔍 1. Wake Tablet (BLE) & Auto-Find IP"
            setOnClickListener {
                statusText.text = "Sending BLE Wake Pulse & searching network..."
                serviceHelper.sendBleWakePulse(this@MainActivity)
                serviceHelper.broadcastFindTablet()
            }
        }

        val btnReceiveMode = Button(this).apply {
            text = "📥 TABLET MODE: Start Always-On Receiver"
            setOnClickListener { startTabletReceiver() }
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
            setPadding(0, 36, 0, 16)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        statusText = TextView(this).apply {
            text = "Tip: Connect both devices to the same Wi-Fi or Phone Hotspot."
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
            runOnUiThread {
                ipInput.setText(foundIp)
                prefs.edit().putString("saved_ip", foundIp).apply()
                statusText.text = "✅ Auto-Found Tablet at $foundIp!"
            }
        }

        // Handle 1-Tap Share from Android Gallery / File Manager
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
        statusText.text = "🟢 Always-On Receiver Active (Port 9500)..."
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
                    statusText.text = "✅ Saved to Downloads: ${savedFile.name}\nReady for next file..."
                } catch (e: Exception) {
                    statusText.text = "Receiver note: ${e.message}"
                    delay(1000)
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
        if (targetIp.isEmpty()) {
            statusText.text = "⚠️ Enter or Auto-Find Tablet IP first!"
            return
        }
        getSharedPreferences("warp_prefs", Context.MODE_PRIVATE)
            .edit().putString("saved_ip", targetIp).apply()

        scope.launch {
            try {
                statusText.text = "Preparing zero-copy file channel..."
                val tempFile = withContext(Dispatchers.IO) { copyUriToTempFile(uri) }
                WarpEngine.sendFile(targetIp, tempFile) { speed, pct ->
                    runOnUiThread {
                        speedText.text = String.format("%.1f MB/s", speed)
                        progressBar.progress = pct
                        statusText.text = "Blasting ${tempFile.name} ($pct%)"
                    }
                }
                statusText.text = "🚀 Transfer Complete! Sent ${tempFile.name}"
                tempFile.delete()
            } catch (e: Exception) {
                statusText.text = "Send error: ${e.message}"
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        var fileName = "warpshare_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        val tempFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output, 4 * 1024 * 1024) }
        }
        return tempFile
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return if (ip == 0) "Connect Wi-Fi / Hotspot" else Formatter.formatIpAddress(ip)
    }
}
