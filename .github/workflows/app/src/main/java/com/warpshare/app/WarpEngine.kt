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
     * Guarantees 100% of bytes are actually transmitted over the network!
     */
    suspend fun sendUriDirectly(
        contentResolver: ContentResolver,
        targetIp: String,
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: (sentMB: Double, totalMB: Double, speedMBps: Double, percent: Int) -> Unit
    ) = coroutineScope {
        if (totalSize <= 0L) throw Exception("File size is 0 bytes!")
        val startTime = System.currentTimeMillis()
        val totalSent = AtomicLong(0L)
        val totalMB = totalSize / (1024.0 * 1024.0)

        // 1. Send Metadata Header & Wait for Tablet to Pre-Allocate File on Disk
        val tabletSavePath = withContext(Dispatchers.IO) {
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

                            // Send 16-byte Chunk Header: [offset, length]
                            val header = ByteBuffer.allocate(16)
                            header.putLong(offset)
                            header.putLong(length)
                            header.flip()
                            while (header.hasRemaining()) socketChannel.write(header)

                            // Direct 512 KB NIO memory buffer per thread (zero GC lag, 100% reliable)
                            val buffer = ByteBuffer.allocateDirect(512 * 1024)
                            var remaining = length
                            var currentOffset = offset

                            while (remaining > 0) {
                                buffer.clear()
                                if (remaining < buffer.capacity()) {
                                    buffer.limit(remaining.toInt())
                                }
                                // Positional read is 100% thread-safe and never returns 0 early!
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

                            // Wait for Tablet to verify every byte of this chunk is on disk!
                            val ack = ByteBuffer.allocate(1)
                            val ackRead = socketChannel.read(ack)
                            if (ackRead != 1 || ack.get(0) != 1.toByte()) {
                                throw Exception
