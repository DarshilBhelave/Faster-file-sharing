package com.warpshare.app

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object WarpEngine {
    private const val PORT_META = 9500
    private const val PORT_DATA = 9501
    private const val PARALLEL_STREAMS = 4
    private const val DIRECT_BUFFER_BYTES = 4 * 1024 * 1024 // 4 MB Direct RAM block per thread
    private const val SOCKET_BUFFER_BYTES = 8 * 1024 * 1024 // 8 MB Kernel TCP Window

    @Volatile var isTransferring = false
    val isReceiverRunning = AtomicBoolean(false)

    private var persistentMetaServer: ServerSocket? = null
    private var persistentDataServer: ServerSocketChannel? = null

    @Synchronized
    private fun getOrBindServers(): Pair<ServerSocket, ServerSocketChannel> {
        var meta = persistentMetaServer
        var data = persistentDataServer
        if (meta == null || meta.isClosed) {
            meta = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT_META))
            }
            persistentMetaServer = meta
        }
        if (data == null || !data.isOpen) {
            data = ServerSocketChannel.open().apply {
                socket().reuseAddress = true
                socket().bind(InetSocketAddress(PORT_DATA))
            }
            persistentDataServer = data
        }
        return Pair(meta, data)
    }

    suspend fun sendUriDirectly(
        contentResolver: ContentResolver,
        targetIp: String,
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: (sentMB: Double, totalMB: Double, speedMBps: Double, percent: Int) -> Unit
    ): String = coroutineScope {
        if (totalSize <= 0L) throw Exception("File size is 0 bytes!")
        isTransferring = true
        val totalSent = AtomicLong(0L)
        val lastUiUpdate = AtomicLong(0L)
        val totalMB = totalSize / (1024.0 * 1024.0)

        try {
            val tabletSavePath: String = withContext(Dispatchers.IO) {
                java.net.Socket().use { metaSocket ->
                    metaSocket.connect(InetSocketAddress(targetIp, PORT_META), 6000)
                    val out = DataOutputStream(metaSocket.getOutputStream())
                    val input = DataInputStream(metaSocket.getInputStream())
                    out.writeUTF(fileName)
                    out.writeLong(totalSize)
                    out.flush()
                    val status = input.readUTF()
                    if (!status.startsWith("READY:")) {
                        throw Exception("Tablet rejected file: $status")
                    }
                    status.removePrefix("READY:")
                }
            }

            // Start timer AFTER metadata handshake so small files show true transfer speed!
            val startTime = System.currentTimeMillis()
            val chunkSize = totalSize / PARALLEL_STREAMS

            val jobs = (0 until PARALLEL_STREAMS).map { index ->
                val offset = index * chunkSize
                val length = if (index == PARALLEL_STREAMS - 1) (totalSize - offset) else chunkSize

                async(Dispatchers.IO) {
                    // Each thread opens its own independent FileDescriptor to avoid kernel lock contention!
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).channel.use { fileChannel ->
                            SocketChannel.open().use { socketChannel ->
                                socketChannel.configureBlocking(true)
                                socketChannel.socket().apply {
                                    tcpNoDelay = true
                                    sendBufferSize = SOCKET_BUFFER_BYTES
                                    trafficClass = 0x18
                                }
                                socketChannel.connect(InetSocketAddress(targetIp, PORT_DATA))

                                val header = ByteBuffer.allocate(16)
                                header.putLong(offset)
                                header.putLong(length)
                                header.flip()
                                while (header.hasRemaining()) socketChannel.write(header)

                                val buffer = ByteBuffer.allocateDirect(DIRECT_BUFFER_BYTES)
                                var remaining = length
                                var currentOffset = offset

                                while (remaining > 0) {
                                    buffer.clear()
                                    if (remaining < buffer.capacity()) {
                                        buffer.limit(remaining.toInt())
                                    }
                                    val bytesRead = fileChannel.read(buffer, currentOffset)
                                    if (bytesRead <= 0) throw Exception("EOF at $currentOffset")

                                    buffer.flip()
                                    while (buffer.hasRemaining()) {
                                        socketChannel.write(buffer)
                                    }

                                    remaining -= bytesRead
                                    currentOffset += bytesRead

                                    val sentSoFar = totalSent.addAndGet(bytesRead.toLong())
                                    val now = System.currentTimeMillis()
                                    val prev = lastUiUpdate.get()
                                    if (now - prev >= 150 && lastUiUpdate.compareAndSet(prev, now)) {
                                        val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.01)
                                        val sentMB = sentSoFar / (1024.0 * 1024.0)
                                        val speed = sentMB / elapsedSec
                                        val pct = ((sentSoFar * 100) / totalSize).toInt().coerceAtMost(99)
                                        onProgress(sentMB, totalMB, speed, pct)
                                    }
                                }

                                val ack = ByteBuffer.allocate(1)
                                val ackRead = socketChannel.read(ack)
                                if (ackRead != 1 || ack.get(0) != 1.toByte()) {
                                    throw Exception("Tablet did not confirm chunk #$index!")
                                }
                            }
                        }
                    } ?: throw Exception("Cannot open file descriptor on thread #$index")
                }
            }
            jobs.awaitAll()

            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.01)
            onProgress(totalMB, totalMB, totalMB / elapsedSec, 100)
            tabletSavePath
        } finally {
            isTransferring = false
        }
    }

    suspend fun receiveFile(
        primaryDir: File,
        fallbackDir: File,
        onStart: (fileName: String, totalMB: Double, fullPath: String) -> Unit,
        onProgress: (fileName: String, recMB: Double, totalMB: Double, speedMBps: Double, percent: Int) -> Unit
    ): File = coroutineScope {
        withContext(Dispatchers.IO) {
            val (metaServer, dataServer) = getOrBindServers()

            val metaSocket = metaServer.accept()
            isTransferring = true
            try {
                val input = DataInputStream(metaSocket.getInputStream())
                val out = DataOutputStream(metaSocket.getOutputStream())
                val fileName = input.readUTF()
                val totalSize = input.readLong()
                val totalMB = totalSize / (1024.0 * 1024.0)

                var outFile = File(primaryDir, fileName)
                try {
                    if (!primaryDir.exists()) primaryDir.mkdirs()
                    RandomAccessFile(outFile, "rw").use { it.setLength(totalSize) }
                } catch (_: Exception) {
                    if (!fallbackDir.exists()) fallbackDir.mkdirs()
                    outFile = File(fallbackDir, fileName)
                    RandomAccessFile(outFile, "rw").use { it.setLength(totalSize) }
                }

                onStart(fileName, totalMB, outFile.absolutePath)
                out.writeUTF("READY:${outFile.absolutePath}")
                out.flush()
                metaSocket.close()

                val startTime = System.currentTimeMillis()
                val totalReceived = AtomicLong(0L)
                val lastUiUpdate = AtomicLong(0L)

                val workers = (0 until PARALLEL_STREAMS).map {
                    async(Dispatchers.IO) {
                        dataServer.accept().use { clientChannel ->
                            clientChannel.configureBlocking(true)
                            clientChannel.socket().apply {
                                tcpNoDelay = true
                                receiveBufferSize = SOCKET_BUFFER_BYTES
                                trafficClass = 0x18
                            }

                            val header = ByteBuffer.allocate(16)
                            while (header.hasRemaining()) {
                                if (clientChannel.read(header) < 0) throw Exception("Stream closed")
                            }
                            header.flip()
                            val offset = header.long
                            val length = header.long

                            // Independent RandomAccessFile per thread removes kernel inode lock bottleneck!
                            RandomAccessFile(outFile, "rw").channel.use { threadOutChannel ->
                                val buffer = ByteBuffer.allocateDirect(DIRECT_BUFFER_BYTES)
                                var remaining = length
                                var currentOffset = offset

                                while (remaining > 0) {
                                    buffer.clear()
                                    if (remaining < buffer.capacity()) {
                                        buffer.limit(remaining.toInt())
                                    }
                                    val bytesRead = clientChannel.read(buffer)
                                    if (bytesRead <= 0) throw Exception("Dropped with $remaining left")

                                    buffer.flip()
                                    while (buffer.hasRemaining()) {
                                        val written = threadOutChannel.write(buffer, currentOffset)
                                        currentOffset += written
                                    }
                                    remaining -= bytesRead

                                    val recSoFar = totalReceived.addAndGet(bytesRead.toLong())
                                    val now = System.currentTimeMillis()
                                    val prev = lastUiUpdate.get()
                                    if (now - prev >= 150 && lastUiUpdate.compareAndSet(prev, now)) {
                                        val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.01)
                                        val recMB = recSoFar / (1024.0 * 1024.0)
                                        val speed = recMB / elapsedSec
                                        val pct = ((recSoFar * 100) / totalSize).toInt()
                                        onProgress(fileName, recMB, totalMB, speed, pct)
                                    }
                                }
                            }

                            val ack = ByteBuffer.wrap(byteArrayOf(1))
                            while (ack.hasRemaining()) clientChannel.write(ack)
                        }
                    }
                }
                workers.awaitAll()

                if (totalReceived.get() != totalSize) {
                    throw Exception("Incomplete receive")
                }
                outFile
            } finally {
                isTransferring = false
            }
        }
    }
}
