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
    private const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024 // 4 MB buffer per stream

    /**
     * SENDER (Phone): Streams directly from Uri using 8 parallel zero-copy file channels (No temp file copy delay!)
     */
    suspend fun sendUriDirectly(
        contentResolver: ContentResolver,
        targetIp: String,
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: (speedMBps: Double, percent: Int) -> Unit
    ) = coroutineScope {
        val startTime = System.currentTimeMillis()
        val totalSent = AtomicLong(0L)

        // 1. Send Metadata Header (File Name + Exact Byte Size)
        withContext(Dispatchers.IO) {
            java.net.Socket().use { metaSocket ->
                metaSocket.connect(InetSocketAddress(targetIp, PORT_META), 6000)
                val out = DataOutputStream(metaSocket.getOutputStream())
                out.writeUTF(fileName)
                out.writeLong(totalSize)
                out.flush()
                // Wait for Tablet to confirm storage pre-allocation
                val ack = DataInputStream(metaSocket.getInputStream()).readUTF()
                if (ack != "READY") throw Exception("Tablet error: $ack")
            }
        }

        // 2. Blast 8 Parallel Streams directly from Storage
        val chunkSize = totalSize / PARALLEL_STREAMS
        val jobs = (0 until PARALLEL_STREAMS).map { index ->
            val offset = index * chunkSize
            val length = if (index == PARALLEL_STREAMS - 1) (totalSize - offset) else chunkSize

            async(Dispatchers.IO) {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { fileChannel ->
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

                            var remaining = length
                            var currentOffset = offset
                            while (remaining > 0) {
                                val step = minOf(remaining, 8L * 1024 * 1024)
                                val transferred = fileChannel.transferTo(currentOffset, step, socketChannel)
                                if (transferred <= 0) break
                                remaining -= transferred
                                currentOffset += transferred

                                val sentSoFar = totalSent.addAndGet(transferred)
                                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                                val speed = (sentSoFar / (1024.0 * 1024.0)) / elapsedSec
                                val pct = ((sentSoFar * 100) / totalSize).toInt()
                                onProgress(speed, pct)
                            }
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
    }

    /**
     * RECEIVER (Tablet): Pre-allocates file on disk and writes all 8 streams concurrently.
     */
    suspend fun receiveFile(
        destDir: File,
        onProgress: (fileName: String, speedMBps: Double, percent: Int) -> Unit
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

                    if (!destDir.exists()) destDir.mkdirs()
                    val outFile = File(destDir, fileName)

                    try {
                        RandomAccessFile(outFile, "rw").use { raf ->
                            raf.setLength(totalSize)
                        }
                        out.writeUTF("READY")
                        out.flush()
                    } catch (e: Exception) {
                        out.writeUTF("Storage Permission Needed: ${e.message}")
                        out.flush()
                        metaSocket.close()
                        throw e
                    }
                    metaSocket.close()

                    val startTime = System.currentTimeMillis()
                    val totalReceived = AtomicLong(0L)

                    val workers = (0 until PARALLEL_STREAMS).map {
                        async(Dispatchers.IO) {
                            dataServer.accept().use { clientChannel ->
                                clientChannel.configureBlocking(true)
                                clientChannel.socket().apply {
                                    tcpNoDelay = true
                                    receiveBufferSize = SOCKET_BUFFER_BYTES
                                }

                                val header = ByteBuffer.allocate(16)
                                while (header.hasRemaining()) clientChannel.read(header)
                                header.flip()
                                val offset = header.long
                                val length = header.long

                                RandomAccessFile(outFile, "rw").channel.use { fileChannel ->
                                    var remaining = length
                                    var currentOffset = offset
                                    while (remaining > 0) {
                                        val step = minOf(remaining, 8L * 1024 * 1024)
                                        val read = fileChannel.transferFrom(clientChannel, currentOffset, step)
                                        if (read <= 0) break
                                        remaining -= read
                                        currentOffset += read

                                        val recSoFar = totalReceived.addAndGet(read)
                                        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                                        val speed = (recSoFar / (1024.0 * 1024.0)) / elapsedSec
                                        val pct = ((recSoFar * 100) / totalSize).toInt()
                                        onProgress(fileName, speed, pct)
                                    }
                                }
                            }
                        }
                    }
                    workers.awaitAll()
                    outFile
                }
            }
        }
    }
}
