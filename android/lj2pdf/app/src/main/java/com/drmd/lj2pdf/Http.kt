package com.drmd.lj2pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Pooled HTTP engine (OkHttp): keeps sockets alive across the many post/image
 * fetches and multiplexes over HTTP/2 where the server supports it, so the modem
 * is used to the full. Transparent gzip. A single shared client; concurrency is
 * gated by its [okhttp3.Dispatcher] (tunable) plus the callers' own semaphores.
 *
 * The [EventListener] feeds two live gauges: total over-the-wire bytes
 * ([ConvertBus.bytesTotal]) and in-flight requests ([ConvertBus.activeRequests]).
 */
object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36 lj2pdf/3.1"
    private const val MAX_BYTES = 12L * 1024 * 1024   // 12 MB cap per resource

    @Volatile private var client: OkHttpClient = build(8, 25_000)

    /** Apply the user's network settings before a job (threads + timeout). */
    @Synchronized
    fun configure(threads: Int, timeoutMs: Long) {
        val t = threads.coerceIn(1, 32)
        client = build(t, timeoutMs.coerceIn(5_000, 120_000))
    }

    private fun build(threads: Int, timeoutMs: Long): OkHttpClient {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = threads * 2
            maxRequestsPerHost = threads
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(threads, 5, TimeUnit.MINUTES))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs * 3, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .eventListener(CountingListener)
            .build()
    }

    /** Raw bytes of [url] (≤[MAX_BYTES]), or null on failure. */
    suspend fun getBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            ConvertBus.activeRequests.incrementAndGet()
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,image/*,*/*")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    return@withContext body.byteStream().readCapped()
                }
            } catch (_: Throwable) {
                if (attempt >= 2) return@withContext null
            } finally {
                ConvertBus.activeRequests.decrementAndGet()
            }
        }
        null
    }

    /** UTF-8 string body of [url], or null. */
    suspend fun getString(url: String): String? =
        getBytes(url)?.toString(Charsets.UTF_8)

    /** Parsed jsoup [Document] with [url] as base URI (for absUrl), or null. */
    suspend fun doc(url: String): Document? =
        getString(url)?.let { Jsoup.parse(it, url) }

    private fun java.io.InputStream.readCapped(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Counts real network bytes + tracks request lifetime for the gauges. */
    private object CountingListener : EventListener() {
        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
            ConvertBus.bytesTotal.addAndGet(response.headers.byteCount())
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            ConvertBus.bytesTotal.addAndGet(byteCount)
        }
    }
}
