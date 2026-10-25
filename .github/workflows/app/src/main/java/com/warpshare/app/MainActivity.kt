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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Ask for Android 16 "All Files Access" so /Download/WarpShare works directly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Turn ON 'All files access' on BOTH Phone and Tablet!", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        // 2. Start Hotspot Radar Service
        try {
            val svc = Intent(this, RadioWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "⚡ WARPSHARE v4.0"
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
            setPadding(0, 36, 0, 8)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            minimumHeight = 28
            setPadding(0, 16, 0, 16)
        }

        statusText = TextView(this).apply {
            text = "🟢 Ready to Send or Receive automatically"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        pathBox = TextView(this).apply {
            text = getSavedFilesSummary()
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

        val openFolderButton = Button(this).apply {
            text = "📂 OPEN DOWNLOADS"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = 28f
            }
            setOnClickListener {
                try {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (_: Exception) {}
            }
        }

        root.addView(title)
        root.addView(Space(this).apply { minimumHeight = 20 })
        root.addView(connectionBadge)
        root.addView(speedText)
        root.addView(progressBar)
        root.addView(statusText)
        root.addView(pathBox)
        root.addView(Space(this).apply { minimumHeight = 32 })
        root.addView(sendButton)
        root.addView(Space(this).apply { minimumHeight = 16 })
        root.addView(openFolderButton)
        setContentView(root)

        startAutoReceiverLoop()

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

        if (intent?.action == Intent.ACTION_SEND) {
            val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) blastFileNow(sharedUri)
        }
    }

    private fun getPrimarySaveFolder(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "WarpShare")
    }

    private fun getFallbackSaveFolder(): File {
        return externalMediaDirs.firstOrNull() ?: getExternalFilesDir(null) ?: filesDir
    }

    private fun getSavedFilesSummary(): String {
        val primary = getPrimarySaveFolder()
        val fallback = getFallbackSaveFolder()
        val files = (primary.listFiles()?.toList() ?: emptyList()) +
                    (fallback.listFiles()?.toList() ?: emptyList())
        return if (files.isEmpty()) {
            "📁 Save Location:\nInternal Storage > Download > WarpShare\n(No files received yet)"
        } else {
            val latest = files.maxByOrNull { it.lastModified() }!!
            "✅ Latest File on Disk:\
