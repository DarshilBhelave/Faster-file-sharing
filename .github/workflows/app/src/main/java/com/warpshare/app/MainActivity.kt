package com.warpshare.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var connectionBadge: TextView
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Prompt once for Android 16 "All Files Access" so /Download/ never fails with Permission Denied
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Please turn ON 'All files access' so WarpShare can save files!", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        // 2. Start Auto-Discovery Hotspot Radar Service
        try {
            val svc = Intent(this, RadioWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (_: Exception) {}

        // 3. Build Super Clean 1-Button UI
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 80, 56, 56)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "⚡ WARPSHARE"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        connectionBadge = TextView(this).apply {
            text = "⏳ Waiting for Hotspot connection..."
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(36, 24, 36, 24)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 40f
            }
        }

        val spacer1 = Space(this).apply { minimumHeight = 70 }

        speedText = TextView(this).apply {
            text = "0.0 MB/s"
            textSize = 48f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4ADE80"))
            gravity = Gravity.CENTER
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            minimumHeight = 24
            setPadding(0, 24, 0, 24)
        }

        statusText = TextView(this).apply {
            text = "Receiver is ALWAYS ON automatically.\nFiles save straight to your Downloads folder."
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 70)
        }

        sendButton = Button(this).apply {
            text = "🚀 SEND FILE"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B0F19"))
            setPadding(40, 48, 40, 48)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = 36f
            }
            setOnClickListener { pickFile() }
        }

        root.addView(title)
        root.addView(Space(this).apply { minimumHeight = 36 })
        root.addView(connectionBadge)
        root.addView(spacer1)
        root.addView(speedText)
        root.addView(progressBar)
        root.addView(statusText)
        root.addView(sendButton)
        setContentView(root)

        // 4. Automatically start the 8-Stream Receiver in the background on BOTH devices!
        startAutoReceiverLoop()

        // 5. Listen for Hotspot Radar Auto-Connect
        RadioWakeService.onPeerConnected = { foundIp ->
            runOnUiThread {
                connectionBadge.text = "🟢 Connected to Device ($foundIp)"
                connectionBadge.setTextColor(Color.parseColor("#4ADE80"))
            }
        }
        // If already found before UI opened
        RadioWakeService.peerIp?.let { foundIp ->
            connectionBadge.text = "🟢 Connected to Device ($foundIp)"
            connectionBadge.setTextColor(Color.parseColor("#4ADE80"))
        }

        // 6. Handle direct sharing from Gallery / File Manager
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) {
                blastFileNow(sharedUri)
            }
        }
    }

    /**
     * Runs automatically on both Phone and Tablet—no button press needed!
     */
    private fun startAutoReceiverLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val savedFile = WarpEngine.receiveFile(downloads) { name, speed, pct ->
                        runOnUiThread {
                            speedText.text = String.format("%.1f MB/s", speed)
                            progressBar.progress = pct
                            statusText.text = "📥 Receiving: $name ($pct%)"
                        }
                    }
                    statusText.text = "✅ Saved to Downloads:\n${savedFile.name}"
                } catch (_: Exception) {
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
            data?.data?.let { blastFileNow(it) }
        }
    }

    private fun blastFileNow(uri: Uri) {
        scope.launch {
            var targetIp = RadioWakeService.peerIp
            if (targetIp == null) {
                statusText.text = "🔍 Searching Hotspot for other device (make sure WarpShare is open on both)..."
                // Wait up to 4 seconds for radar to lock on
                repeat(8) {
                    delay(500)
                    targetIp = RadioWakeService.peerIp
                    if (targetIp != null) return@repeat
                }
            }

            val finalIp = targetIp
            if (finalIp == null) {
                statusText.text = "⚠️ Could not find other device! Turn on Hotspot, connect Tablet, and open WarpShare on both screens."
                return@launch
            }

            try {
                val (fileName, fileSize) = getFileMeta(uri)
                statusText.text = "🚀 Blasting $fileName..."
                WarpEngine.sendUriDirectly(contentResolver, finalIp, uri, fileName, fileSize) { speed, pct ->
                    runOnUiThread {
                        speedText.text = String.format("%.1f MB/s", speed)
                        progressBar.progress = pct
                        statusText.text = "🚀 Sending: $fileName ($pct%)"
                    }
                }
                statusText.text = "✅ Done! Sent $fileName"
            } catch (e: Exception) {
                statusText.text = "❌ Error: ${e.message}\n(Make sure 'All files access' is ON on Tablet!)"
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
}
