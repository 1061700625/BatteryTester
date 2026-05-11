package com.xfxuezhang.batterytester.load

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class NetworkTransferLoad {
    private var job: Job? = null

    @Volatile
    private var enabled: Boolean = false

    private val downloadedBytes = AtomicLong(0L)

    val isRunning: Boolean
        get() = enabled && job?.isActive == true

    val totalDownloadedBytes: Long
        get() = downloadedBytes.get()

    fun start(
        scope: CoroutineScope,
        url: String = DEFAULT_DOWNLOAD_URL,
        limitBytes: Long? = null
    ) {
        enabled = true
        if (job?.isActive == true) return
        val safeUrl = url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: DEFAULT_DOWNLOAD_URL
        job = scope.launch(Dispatchers.IO) {
            while (isActive && enabled && !hasReachedLimit(limitBytes)) {
                runCatching { downloadSmallChunk(safeUrl, limitBytes) }
                delay(300L)
            }
            if (hasReachedLimit(limitBytes)) enabled = false
        }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        job = null
    }

    fun resetStats() {
        downloadedBytes.set(0L)
    }

    private fun downloadSmallChunk(url: String, limitBytes: Long?) {
        val remaining = limitBytes?.let { it - downloadedBytes.get() }
        if (remaining != null && remaining <= 0L) return
        val maxThisRequest = remaining?.coerceAtMost(MAX_BYTES_PER_REQUEST.toLong())?.toInt() ?: MAX_BYTES_PER_REQUEST

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("User-Agent", "ChargeDischargeMonitor/1.0")
            setRequestProperty("Range", "bytes=0-${maxThisRequest - 1}")
        }
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                val buffer = ByteArray(16 * 1024)
                var readTotal = 0
                while (readTotal < maxThisRequest && !hasReachedLimit(limitBytes)) {
                    val maxRead = minOf(buffer.size, maxThisRequest - readTotal)
                    val read = input.read(buffer, 0, maxRead)
                    if (read <= 0) break
                    readTotal += read
                    downloadedBytes.addAndGet(read.toLong())
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun hasReachedLimit(limitBytes: Long?): Boolean {
        return limitBytes != null && limitBytes > 0L && downloadedBytes.get() >= limitBytes
    }

    companion object {
        const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=262144"
        private const val MAX_BYTES_PER_REQUEST = 256 * 1024
    }
}
