package com.warpshare.app

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.*
import java.io.*
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
    private const val STREAMS = 4
    private const val BUF_SIZE = 4 * 1024 * 1024
    private const val SOCK_BUF = 8 * 1024 * 1024

    @Volatile var isTransferring = false
    val isReceiverRunning = AtomicBoolean(false)

    private var metaSrv: ServerSocket? = null
    private var dataSrv: ServerSocketChannel? = null

    @Synchronized
    private fun getServers(): Pair<ServerSocket, ServerSocketChannel> {
        var m = metaSrv
        if (m == null || m.isClosed) {
            m = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT_META)) }
            metaSrv = m
        }
        var d = dataSrv
        if (d == null || !d.isOpen) {
            d = ServerSocketChannel.open().apply { socket().reuseAddress = true; socket().bind(InetSocketAddress(PORT_DATA)) }
            dataSrv = d
        }
        return Pair(m!!, d!!)
    }

    suspend fun sendUriDirectly(
        contentResolver: ContentResolver,
        targetIp: String,
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: (Double, Double, Double, Int) -> Unit
    ): String = coroutineScope {
        if (totalSize <= 0L) throw Exception("File size is 0 bytes!")
        isTransferring = true
        val totalSent = AtomicLong(0L)
        val lastUi = AtomicLong(0L)
        val totalMB = totalSize / 1048576.0

        try {
            val savedPath = withContext(Dispatchers.IO) {
                java.net.Socket().use { s ->
                    s.connect(InetSocketAddress(targetIp, PORT_META), 6000)
                    val out = DataOutputStream(s.getOutputStream())
                    val inp = DataInputStream(s.getInputStream())
                    out.writeUTF(fileName)
                    out.writeLong(totalSize)
                    out.flush()
                    val res = inp.readUTF()
                    if (!res.startsWith("READY:")) throw Exception("Rejected: $res")
                    res.removePrefix("READY:")
                }
            }

            val start = System.currentTimeMillis()
            val chunk = totalSize / STREAMS

            val jobs = (0 until STREAMS).map { i ->
                val offset = i * chunk
                val len = if (i == STREAMS - 1) (totalSize - offset) else chunk
                async(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).channel.use { fc ->
                            SocketChannel.open().use { sc ->
                                sc.configureBlocking(true)
                                sc.socket().apply { tcpNoDelay = true; sendBufferSize = SOCK_BUF; trafficClass = 0x18 }
                                sc.connect(InetSocketAddress(targetIp, PORT_DATA))

                                val hdr = ByteBuffer.allocate(16).putLong(offset).putLong(len)
                                hdr.flip()
                                while (hdr.hasRemaining()) sc.write(hdr)

                                val buf = ByteBuffer.allocateDirect(BUF_SIZE)
                                var rem = len
                                var pos = offset
                                while (rem > 0) {
                                    buf.clear()
                                    if (rem < buf.capacity()) buf.limit(rem.toInt())
                                    val n = fc.read(buf, pos)
                                    if (n <= 0) throw Exception("EOF at $pos")
                                    buf.flip()
                                    while (buf.hasRemaining()) sc.write(buf)
                                    rem -= n
                                    pos += n

                                    val sent = totalSent.addAndGet(n.toLong())
                                    val now = System.currentTimeMillis()
                                    val prev = lastUi.get()
                                    if (now - prev >= 150 && lastUi.compareAndSet(prev, now)) {
                                        val sec = ((now - start) / 1000.0).coerceAtLeast(0.01)
                                        val mb = sent / 1048576.0
                                        onProgress(mb, totalMB, mb / sec, ((sent * 100) / totalSize).toInt().coerceAtMost(99))
                                    }
                                }
                                val ack = ByteBuffer.allocate(1)
                                if (sc.read(ack) != 1) throw Exception("No ACK #$i")
                            }
                        }
                    } ?: throw Exception("Cannot open file")
                }
            }
            jobs.awaitAll()
            val sec = ((System.currentTimeMillis() - start) / 1000.0).coerceAtLeast(0.01)
            onProgress(totalMB, totalMB, totalMB / sec, 100)
            savedPath
        } finally {
            isTransferring = false
        }
    }

    suspend fun receiveFile(
        primaryDir: File,
        fallbackDir: File,
        onStart: (String, Double, String) -> Unit,
        onProgress: (String, Double, Double, Double, Int) -> Unit
    ): File = coroutineScope {
        withContext(Dispatchers.IO) {
            val (metaServer, dataServer) = getServers()
            val metaSocket = metaServer.accept()
            isTransferring = true
            try {
                val inp = DataInputStream(metaSocket.getInputStream())
                val out = DataOutputStream(metaSocket.getOutputStream())
                val fileName = inp.readUTF()
                val totalSize = inp.readLong()
                val totalMB = totalSize / 1048576.0

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

                val start = System.currentTimeMillis()
                val totalRec = AtomicLong(0L)
                val lastUi = AtomicLong(0L)

                val workers = (0 until STREAMS).map {
                    async(Dispatchers.IO) {
                        dataServer.accept().use { sc ->
                            sc.configureBlocking(true)
                            sc.socket().apply { tcpNoDelay = true; receiveBufferSize = SOCK_BUF; trafficClass = 0x18 }

                            val hdr = ByteBuffer.allocate(16)
                            while (hdr.hasRemaining()) if (sc.read(hdr) < 0) throw Exception("Closed")
                            hdr.flip()
                            var pos = hdr.long
                            var rem = hdr.long

                            RandomAccessFile(outFile, "rw").channel.use { fc ->
                                val buf = ByteBuffer.allocateDirect(BUF_SIZE)
                                while (rem > 0) {
                                    buf.clear()
                                    if (rem < buf.capacity()) buf.limit(rem.toInt())
                                    val n = sc.read(buf)
                                    if (n <= 0) throw Exception("Dropped")
                                    buf.flip()
                                    while (buf.hasRemaining()) pos += fc.write(buf, pos)
                                    rem -= n

                                    val rec = totalRec.addAndGet(n.toLong())
                                    val now = System.currentTimeMillis()
                                    val prev = lastUi.get()
                                    if (now - prev >= 150 && lastUi.compareAndSet(prev, now)) {
                                        val sec = ((now - start) / 1000.0).coerceAtLeast(0.01)
                                        val mb = rec / 1048576.0
                                        onProgress(fileName, mb, totalMB, mb / sec, ((rec * 100) / totalSize).toInt())
                                    }
                                }
                            }
                            val ack = ByteBuffer.wrap(byteArrayOf(1))
                            while (ack.hasRemaining()) sc.write(ack)
                        }
                    }
                }
                workers.awaitAll()
                if (totalRec.get() != totalSize) throw Exception("Incomplete")
                outFile
            } finally {
                isTransferring = false
            }
        }
    }
}
