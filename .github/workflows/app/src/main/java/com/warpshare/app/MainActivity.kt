package com.warpshare.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
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

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var connectionBadge: TextView
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private lateinit var pathBox: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sendButton: Button
    private lateinit var openFolderButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Ensure Android 16 "All Files Access" is ON so /Download/WarpShare/ can be written
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Turn ON 'All files access' so WarpShare can save files!", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        // 2. Start Auto-Discovery Hotspot Radar Service
        try {
            val svc = Intent(this, RadioWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (_: Exception) {}

        val saveFolder = getWarpSaveFolder()

        // 3. Build Clean Status & Storage Location UI
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "⚡ WARPSHARE v3.1"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        connectionBadge = TextView(this).apply {
            text = "⏳ Waiting for Hotspot connection..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(32, 20, 32, 20)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 36f
            }
        }

        speedText = TextView(this).apply {
            text = "0.0 MB/s"
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4ADE80"))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 8)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            minimumHeight = 28
            setPadding(0, 16, 0, 16)
        }

        statusText = TextView(this).apply {
            text = "🟢 Ready to Send or Receive automatically"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        // Dedicated Box showing EXACTLY where files are saved on Tablet/Phone
        pathBox = TextView(this).apply {
            text = "📁 Save Location:\nInternal Storage > Download > WarpShare\n(${saveFolder.absolutePath})"
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111827"))
                setStroke(2, Color.parseColor("#1E3A8A"))
                cornerRadius = 24f
            }
        }

        sendButton = Button(this).apply {
            text = "🚀 SEND FILE"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B0F19"))
            setPadding(40, 42, 40, 42)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = 32f
            }
            setOnClickListener { pickFile() }
        }

        openFolderButton = Button(this).apply {
            text = "📂 OPEN DOWNLOADS FOLDER"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = 28f
            }
            setOnClickListener { openDownloadsApp() }
        }

        root.addView(title)
        root.addView(Space(this).apply { minimumHeight = 24 })
        root.addView(connectionBadge)
        root.addView(speedText)
        root.addView(progressBar)
        root.addView(statusText)
        root.addView(pathBox)
        root.addView(Space(this).apply { minimumHeight = 36 })
        root.addView(sendButton)
        root.addView(Space(this).apply { minimumHeight = 20 })
        root.addView(openFolderButton)
        setContentView(root)

        // 4. Start Always-On Receiver Loop
        startAutoReceiverLoop()

        // 5. Update Badge when Hotspot Radar locks onto the other device
        RadioWakeService.onPeerConnected = { foundIp ->
            runOnUiThread {
                connectionBadge.text = "🟢 Connected to Device ($foundIp)"
                connectionBadge.setTextColor(Color.parseColor("#4ADE80"))
            }
        }
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

    private fun getWarpSaveFolder(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val warpDir = File(downloads, "WarpShare")
        if (!warpDir.exists()) warpDir.mkdirs()
        return warpDir
    }

    /**
     * TABLET RECEIVER LOOP: Updates Tablet UI live & scans file into Android Gallery/My Files!
     */
    private fun startAutoReceiverLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val warpFolder = getWarpSaveFolder()
                    val savedFile = WarpEngine.receiveFile(
                        destDir = warpFolder,
                        onStart = { fileName, totalMB, fullPath ->
                            runOnUiThread {
                                progressBar.progress = 0
                                statusText.text = "📥 INCOMING FILE:\n$fileName (${String.format("%.1f", totalMB)} MB)"
                                statusText.setTextColor(Color.parseColor("#FACC15"))
                                pathBox.text = "⏳ Saving to:\n$fullPath"
                            }
                        },
                        onProgress = { name, recMB, totalMB, speed, pct ->
                            runOnUiThread {
                                speedText.text = String.format("%.1f MB/s", speed)
                                progressBar.progress = pct
                                statusText.text = "📥 Receiving: $name\n${String.format("%.0f", recMB)} MB / ${String.format("%.0f", totalMB)} MB ($pct%)"
                            }
                        }
                    )

                    // Index the new file immediately so Samsung My Files & Gallery show it at the top!
                    MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(savedFile.absolutePath),
                        null,
                        null
                    )

                    runOnUiThread {
                        progressBar.progress = 100
                        statusText.text = "✅ RECEIVED & SAVED:\n${savedFile.name} (${savedFile.length() / (1024 * 1024)} MB)"
                        statusText.setTextColor(Color.parseColor("#4ADE80"))
                        pathBox.text = "✅ Stored in:\nInternal Storage > Download > WarpShare > ${savedFile.name}\n(Full path: ${savedFile.absolutePath})"
                        Toast.makeText(this@MainActivity, "File saved to Download/WarpShare!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    delay(1000)
                }
            }
        }
    }

    private fun openDownloadsApp() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "Open 'My Files' -> Internal Storage -> Download -> WarpShare", Toast.LENGTH_LONG).show()
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
                statusText.text = "🔍 Searching Hotspot for Tablet..."
                repeat(8) {
                    delay(500)
                    targetIp = RadioWakeService.peerIp
                    if (targetIp != null) return@repeat
                }
            }

            val finalIp = targetIp
            if (finalIp == null) {
                statusText.text = "⚠️ Other device not found! Connect Hotspot & open WarpShare on both screens."
                return@launch
            }

            try {
                val (fileName, fileSize) = getFileMeta(uri)
                statusText.setTextColor(Color.parseColor("#FACC15"))
                statusText.text = "🚀 Connecting to $finalIp..."
                WarpEngine.sendUriDirectly(contentResolver, finalIp, uri, fileName, fileSize) { sentMB, totalMB, speed, pct ->
                    runOnUiThread {
                        speedText.text = String.format("%.1f MB/s", speed)
                        progressBar.progress = pct
                        statusText.text = "🚀 Sending: $fileName\n${String.format("%.0f", sentMB)} MB / ${String.format("%.0f", totalMB)} MB ($pct%)"
                    }
                }
                statusText.setTextColor(Color.parseColor("#4ADE80"))
                statusText.text = "✅ 100% Sent & Verified on Tablet!\n$fileName"
                pathBox.text = "✅ Saved on Tablet inside:\nInternal Storage > Download > WarpShare > $fileName"
            } catch (e: Exception) {
                statusText.setTextColor(Color.parseColor("#F87171"))
                statusText.text = "❌ Error: ${e.message}\n(Check 'All files access' permission on Tablet!)"
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
