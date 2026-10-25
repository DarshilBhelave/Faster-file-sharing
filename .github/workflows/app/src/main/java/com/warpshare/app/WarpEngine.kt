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
     * SENDER (Phone): Streams 8 parallel chunks and waits for Tablet's final disk-write confirmation.
     */
    suspend fun sendUriDirectly(
        contentResolver: ContentResolver,
        targetIp: String,
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: (sentMB: Double, totalMB: Double, speedMBps: Double, percent: Int) -> Unit
    ) = coroutineScope {
        val startTime = System.currentTimeMillis()
        val totalSent = AtomicLong(0L)
        val totalMB = totalSize / (1024.0 * 1024.0)

        // 1. Send Metadata Header (File Name + Exact Byte Size)
        withContext(Dispatchers.IO) {
            java.net.Socket().use { metaSocket ->
                metaSocket.connect(InetSocketAddress(targetIp, PORT_META), 6000)
                val out = DataOutputStream(metaSocket.getOutputStream())
                out.writeUTF(fileName)
                out.writeLong(totalSize)
                out.flush()
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
                                val step = minOf(remaining, 4L * 1024 * 1024)
                                val transferred = fileChannel.transferTo(currentOffset, step, socketChannel)
                                if (transferred <= 0) break
                                remaining -= transferred
                                currentOffset += transferred

                                val sentSoFar = totalSent.addAndGet(transferred)
                                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                                val sentMB = sentSoFar / (1024.0 * 1024.0)
                                val speed = sentMB / elapsedSec
                                val pct = ((sentSoFar * 100) / totalSize).toInt().coerceAtMost(99)
                                onProgress(sentMB, totalMB, speed, pct)
                            }

                            // CRITICAL: Wait for Tablet to confirm this chunk is 100% written to disk before closing socket!
                            val ackBuf = ByteBuffer.allocate(1)
                            while (ackBuf.hasRemaining()) {
                                if (socketChannel.read(ackBuf) < 0) break
                            }
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
        onProgress(totalMB, totalMB, totalMB / elapsedSec, 100)
    }

    /**
     * RECEIVER (Tablet): Pre-allocates file, shows live incoming status on Tablet UI, and sends final ACK.
     */
    suspend fun receiveFile(
        destDir: File,
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

                    if (!destDir.exists()) destDir.mkdirs()
                    val outFile = File(destDir, fileName)

                    try {
                        RandomAccessFile(outFile, "rw").use { raf ->
                            raf.setLength(totalSize)
                        }
                        // Immediately notify Tablet UI that a file is incoming!
                        onStart(fileName, totalMB, outFile.absolutePath)
                        out.writeUTF("READY")
                        out.flush()
                    } catch (e: Exception) {
                        out.writeUTF("Tablet Storage Permission Error: ${e.message}")
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
                                        val step = minOf(remaining, 4L * 1024 * 1024)
                                        val read = fileChannel.transferFrom(clientChannel, currentOffset, step)
                                        if (read <= 0) break
                                        remaining -= read
                                        currentOffset += read

                                        val recSoFar = totalReceived.addAndGet(read)
                                        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                                        val recMB = recSoFar / (1024.0 * 1024.0)
                                        val speed = recMB / elapsedSec
                                        val pct = ((recSoFar * 100) / totalSize).toInt()
                                        onProgress(fileName, recMB, totalMB, speed, pct)
                                    }
                                    // Flush to physical storage and send 1-byte ACK to Phone
                                    fileChannel.force(false)
                                }
                                val ack = ByteBuffer.wrap(byteArrayOf(1))
                                while (ack.hasRemaining()) clientChannel.write(ack)
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
