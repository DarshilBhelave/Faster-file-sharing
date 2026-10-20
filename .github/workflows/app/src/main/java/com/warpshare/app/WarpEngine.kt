package com.warpshare.app

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

object WarpEngine {
    private const val PORT_META = 9500
    private const val PORT_DATA = 9501
    private const val PARALLEL_STREAMS = 8
    private const val SOCKET_BUFFER_BYTES = 4 * 1024 * 1024 // 4 MB buffer per stream

    /**
     * SENDER (Phone): Splits file into 8 equal chunks and blasts them in parallel using Zero-Copy NIO.
     */
    suspend fun sendFile(
        targetIp: String,
        file: File,
        onProgress: (speedMBps: Double, percent: Int) -> Unit
    ) = coroutineScope {
        val totalSize = file.length()
        val startTime = System.currentTimeMillis()
        val totalSent = AtomicLong(0L)

        // 1. Send Metadata Header (File Name + Exact Byte Size)
        withContext(Dispatchers.IO) {
            java.net.Socket().use { metaSocket ->
                metaSocket.connect(InetSocketAddress(targetIp, PORT_META), 5000)
                val out = DataOutputStream(metaSocket.getOutputStream())
                out.writeUTF(file.name)
                out.writeLong(totalSize)
                out.flush()
                // Wait for tablet to confirm storage pre-allocation
                DataInputStream(metaSocket.getInputStream()).readByte()
            }
        }

        // 2. Calculate 8 Chunk Ranges
        val chunkSize = totalSize / PARALLEL_STREAMS
        val jobs = (0 until PARALLEL_STREAMS).map { index ->
            val offset = index * chunkSize
            val length = if (index == PARALLEL_STREAMS - 1) (totalSize - offset) else chunkSize

            async(Dispatchers.IO) {
                RandomAccessFile(file, "r").channel.use { fileChannel ->
                    SocketChannel.open().use { socketChannel ->
                        socketChannel.configureBlocking(true)
                        socketChannel.socket().apply {
                            tcpNoDelay = true
                            sendBufferSize = SOCKET_BUFFER_BYTES
                        }
                        socketChannel.connect(InetSocketAddress(targetIp, PORT_DATA))

                        // Write 16-byte Chunk Header: [offset (8B), length (8B)]
                        val header = java.nio.ByteBuffer.allocate(16)
                        header.putLong(offset)
                        header.putLong(length)
                        header.flip()
                        while (header.hasRemaining()) socketChannel.write(header)

                        // Zero-Copy Kernel Transfer Loop
                        var remaining = length
                        var currentOffset = offset
                        while (remaining > 0) {
                            // Transfer in 8 MB zero-copy bursts
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
            ServerSocket(PORT_META).use { metaServer ->
                ServerSocketChannel.open().use { dataServer ->
                    dataServer.socket().bind(InetSocketAddress(PORT_DATA))

                    // 1. Read File Metadata & Pre-Allocate Disk Space
                    val metaSocket = metaServer.accept()
                    val input = DataInputStream(metaSocket.getInputStream())
                    val fileName = input.readUTF()
                    val totalSize = input.readLong()

                    val outFile = File(destDir, fileName)
                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.setLength(totalSize) // Instant unfragmented disk allocation!
                    }

                    // Signal phone that disk is ready for 8-stream blast
                    DataOutputStream(metaSocket.getOutputStream()).writeByte(1)
                    metaSocket.close()

                    val startTime = System.currentTimeMillis()
                    val totalReceived = AtomicLong(0L)

                    // 2. Accept 8 Parallel Data Streams
                    val workers = (0 until PARALLEL_STREAMS).map {
                        async(Dispatchers.IO) {
                            dataServer.accept().use { clientChannel ->
                                clientChannel.configureBlocking(true)
                                clientChannel.socket().apply {
                                    tcpNoDelay = true
                                    receiveBufferSize = SOCKET_BUFFER_BYTES
                                }

                                val header = java.nio.ByteBuffer.allocate(16)
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
