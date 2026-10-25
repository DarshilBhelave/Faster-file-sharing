package com.warpshare.app

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

object WarpEngine {
    private const val PORT_META = 9500
    private const val PORT_DATA = 9501
    private const val PARALLEL_STREAMS = 8
    private const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024 // 4 MB socket buffer

    /**
     * SENDER (Phone): Uses thread-safe positional NIO ByteBuffer.read(buf, offset) across 8 parallel streams.
     */
    suspend fun sendUriDirectly(
        contentResolver: ContentResolver,
        targetIp: String,
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: (sentMB: Double, totalMB: Double, speedMBps: Double, percent: Int) -> Unit
    ): String = coroutineScope {
        if (totalSize <= 0L) throw Exception("File size is 0 bytes!")
        val startTime = System.currentTimeMillis()
        val totalSent = AtomicLong(0L)
        val totalMB = totalSize / (1024.0 * 1024.0)

        // 1. Send Metadata Header & Wait for Tablet to Pre-Allocate File on Disk
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

        // 2. Open ONE shared FileDescriptor and read positionally across 8 parallel coroutines
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Cannot open file on phone")

        try {
            FileInputStream(pfd.fileDescriptor).channel.use { sharedFileChannel ->
                val chunkSize = totalSize / PARALLEL_STREAMS
                val jobs = (0 until PARALLEL_STREAMS).map { index ->
                    val offset = index * chunkSize
                    val length = if (index == PARALLEL_STREAMS - 1) (totalSize - offset) else chunkSize

                    async(Dispatchers.IO) {
                        SocketChannel.open().use { socketChannel ->
                            socketChannel.configureBlocking(true)
                            socketChannel.socket().apply {
                                tcpNoDelay = true
                                sendBufferSize = SOCKET_BUFFER_BYTES
                            }
                            socketChannel.connect(InetSocketAddress(targetIp, PORT_DATA))

                            val header = ByteBuffer.allocate(16)
                            header.putLong(offset)
                            header.putLong(length)
                            header.flip()
                            while (header.hasRemaining()) socketChannel.write(header)

                            val buffer = ByteBuffer.allocateDirect(512 * 1024)
                            var remaining = length
                            var currentOffset = offset

                            while (remaining > 0) {
                                buffer.clear()
                                if (remaining < buffer.capacity()) {
                                    buffer.limit(remaining.toInt())
                                }
                                val bytesRead = sharedFileChannel.read(buffer, currentOffset)
                                if (bytesRead <= 0) throw Exception("Unexpected EOF reading phone file at $currentOffset")

                                buffer.flip()
                                while (buffer.hasRemaining()) {
                                    socketChannel.write(buffer)
                                }

                                remaining -= bytesRead
                                currentOffset += bytesRead

                                val sentSoFar = totalSent.addAndGet(bytesRead.toLong())
                                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                                val sentMB = sentSoFar / (1024.0 * 1024.0)
                                val speed = sentMB / elapsedSec
                                val pct = ((sentSoFar * 100) / totalSize).toInt().coerceAtMost(99)
                                onProgress(sentMB, totalMB, speed, pct)
                            }

                            // Wait for Tablet to verify every byte of this chunk is on disk
                            val ack = ByteBuffer.allocate(1)
                            val ackRead = socketChannel.read(ack)
                            if (ackRead != 1 || ack.get(0) != 1.toByte()) {
                                throw Exception("Tablet did not confirm chunk #$index!")
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }

        if (totalSent.get() != totalSize) {
            throw Exception("Incomplete send: ${totalSent.get()} /$totalSize bytes")
        }
        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
        onProgress(totalMB, totalMB, totalMB / elapsedSec, 100)
        tabletSavePath
    }

    /**
     * RECEIVER (Tablet): Pre-allocates file, verifies 100% byte count, and writes to disk.
     */
    suspend fun receiveFile(
        primaryDir: File,
        fallbackDir: File,
        onStart: (fileName: String, totalMB: Double, fullPath: String) -> Unit,
        onProgress: (fileName: String, recMB: Double, totalMB: Double, speedMBps: Double, percent: Int) -> Unit
    ): File = coroutineScope {
        withContext(Dispatchers.IO) {
            ServerSocket().use { metaServer ->
                metaServer.reuseAddress = true
                metaServer.bind(InetSocketAddress(PORT_META))

                ServerSocketChannel.open().use { dataServer ->
                    dataServer.socket().reuseAddress = true
                    dataServer.socket().bind(InetSocketAddress(PORT_DATA))

                    val metaSocket = metaServer.accept()
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

                    RandomAccessFile(outFile, "rw").channel.use { sharedOutChannel ->
                        val workers = (0 until PARALLEL_STREAMS).map {
                            async(Dispatchers.IO) {
                                dataServer.accept().use { clientChannel ->
                                    clientChannel.configureBlocking(true)
                                    clientChannel.socket().apply {
                                        tcpNoDelay = true
                                        receiveBufferSize = SOCKET_BUFFER_BYTES
                                    }

                                    val header = ByteBuffer.allocate(16)
                                    while (header.hasRemaining()) {
                                        if (clientChannel.read(header) < 0) throw Exception("Stream closed reading header")
                                    }
                                    header.flip()
                                    val offset = header.long
                                    val length = header.long

                                    val buffer = ByteBuffer.allocateDirect(512 * 1024)
                                    var remaining = length
                                    var currentOffset = offset

                                    while (remaining > 0) {
                                        buffer.clear()
                                        if (remaining < buffer.capacity()) {
                                            buffer.limit(remaining.toInt())
                                        }
                                        val bytesRead = clientChannel.read(buffer)
                                        if (bytesRead <= 0) throw Exception("Connection dropped with
