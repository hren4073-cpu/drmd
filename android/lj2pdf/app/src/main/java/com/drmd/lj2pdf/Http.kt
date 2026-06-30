package com.drmd.lj2pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Tiny HTTP GET helper used by the multithreaded HTML downloader. Plain
 * HttpURLConnection (no extra dependency): desktop-ish UA, gzip, redirects,
 * timeouts and one retry. Suspends on Dispatchers.IO so many fetches can run
 * concurrently.
 */
object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36 lj2pdf/3.0"
    private const val CONNECT_MS = 20_000
    private const val READ_MS = 40_000
    private const val MAX_BYTES = 12 * 1024 * 1024   // 12 MB cap per resource

    /** Raw bytes of [url], or null on failure. */
    suspend fun getBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                return@withContext fetch(url)
            } catch (_: Throwable) {
                if (attempt >= 2) return@withContext null
            }
        }
        null
    }

    /** UTF-8 string body of [url] (best-effort charset), or null. */
    suspend fun getString(url: String): String? =
        getBytes(url)?.toString(Charsets.UTF_8)

    /** Parsed jsoup [Document] with [url] as the base URI (for absUrl), or null. */
    suspend fun doc(url: String): Document? =
        getString(url)?.let { Jsoup.parse(it, url) }

    private fun fetch(url: String): ByteArray? {
        var current = url
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*,*/*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) return null
                val raw = (conn.errorStream ?: conn.inputStream)
                val stream = if ((conn.contentEncoding ?: "").contains("gzip", true))
                    GZIPInputStream(raw) else raw
                stream.use { return it.readCapped() }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun java.io.InputStream.readCapped(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
