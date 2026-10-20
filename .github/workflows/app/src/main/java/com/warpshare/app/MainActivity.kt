package com.warpshare.app

import android.Manifest
import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions on launch
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ), 100
        )

        // Start Background Auto-Wake & Low-Latency Service
        val svcIntent = Intent(this, RadioWakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        // Automatically turn on Wi-Fi & BT on launch
        serviceHelper.forceTurnOnRadios(this)

        // Build clean programmatic UI (No XML files needed!)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "⚡ WARPSHARE 8-STREAM"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        }

        val myIp = getLocalIpAddress()
        val ipBanner = TextView(this).apply {
            text = "This Device IP: $myIp"
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 12, 0, 32)
        }

        ipInput = EditText(this).apply {
            hint = "Tablet IP (Auto-filled or type here)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        val btnAutoDiscover = Button(this).apply {
            text = "1. Wake Tablet (BLE) & Auto-Find IP"
            setOnClickListener {
                statusText.text = "Sending BLE Wake Pulse & searching..."
                serviceHelper.sendBleWakePulse(this@MainActivity)
                serviceHelper.broadcastFindTablet()
            }
        }

        val btnReceiveMode = Button(this).apply {
            text = "TABLET MODE: Start 8-Stream Receiver"
            setOnClickListener { startTabletReceiver() }
        }

        val btnPickAndSend = Button(this).apply {
            text = "PHONE MODE: Pick File & Blast (8 Streams)"
            setOnClickListener { pickFile() }
        }

        speedText = TextView(this).apply {
            text = "0.0 MB/s"
            textSize = 32f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4ADE80"))
            setPadding(0, 40, 0, 16)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        statusText = TextView(this).apply {
            text = "Ready. Tap 'Start Receiver' on Tablet, or Share any file from Phone."
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
                statusText.text = "Found Tablet at $foundIp!"
            }
        }

        // Check if user launched WarpShare directly from Android's Share Sheet
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (sharedUri != null) {
                serviceHelper.sendBleWakePulse(this)
                handleSendUri(sharedUri)
            }
        }
    }

    private fun startTabletReceiver() {
        statusText.text = "Listening on Port 9500 (8 Zero-Copy Streams Ready)..."
        scope.launch {
            try {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val savedFile = WarpEngine.receiveFile(downloads) { name, speed, pct ->
                    runOnUiThread {
                        speedText.text = String.format("%.1f MB/s", speed)
                        progressBar.progress = pct
                        statusText.text = "Receiving $name ($pct%)"
                    }
                }
                statusText.text = "✅ Saved to Downloads: ${savedFile.name}"
            } catch (e: Exception) {
                statusText.text = "Receive error: ${e.message}"
            }
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
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
            statusText.text = "Please enter or Auto-Find Tablet IP first!"
            return
        }
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
                statusText.text = "🚀 Transfer Complete!"
                tempFile.delete()
            } catch (e: Exception) {
                statusText.text = "Send error: ${e.message}"
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        var fileName = "warpshare_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        val tempFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output, 1024 * 1024) }
        }
        return tempFile
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return if (ip == 0) "Turn on Wi-Fi / Hotspot" else Formatter.formatIpAddress(ip)
    }
}
