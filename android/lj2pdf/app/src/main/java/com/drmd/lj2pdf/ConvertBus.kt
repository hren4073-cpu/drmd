package com.drmd.lj2pdf

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tiny in-process bus between [ConvertService] (producer) and [MainActivity]
 * (observer). Same process, so a plain singleton is enough — no broadcasts.
 *
 * Callbacks always reach the observer on the MAIN thread: producers run on
 * background dispatchers (e.g. the PDF merge on Dispatchers.IO), and the UI
 * touches Material progress indicators whose animations may only be started on
 * the main thread — so every dispatch is marshalled here.
 */
object ConvertBus {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Run [block] on the main thread now if already there, else post it. */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
    interface Observer {
        fun onScanProgress(pagesScanned: Int, itemsFound: Int)
        /** Scan finished and the service is waiting for the user to pick a count. */
        fun onScanReady(total: Int)
        fun onProgress(done: Int, total: Int, status: String)
        fun onLog(line: String)
        fun onDone(ok: Boolean, book: File?)
        /** Live network/CPU stats: total bytes, speed (B/s), ping ms, active
         *  download threads, ETA seconds (-1 if unknown). */
        fun onStats(bytes: Long, bps: Long, pingMs: Int, threads: Int, etaSec: Int)
    }

    /** Total over-the-wire bytes downloaded this run (fed by the HTTP engine). */
    val bytesTotal = AtomicLong(0)
    /** In-flight HTTP requests right now (download-thread gauge). */
    val activeRequests = AtomicInteger(0)

    @Volatile var observer: Observer? = null
    @Volatile var cancelRequested = false
    @Volatile var running = false
    @Volatile var awaitingSelection = false

    var total = 0
    var done = 0
    var scanTotal = 0
    var lastStatus = "Ready."
    @Volatile var lastBook: File? = null
    val logText = StringBuilder()

    fun start(total: Int) {
        this.total = total
        done = 0
        scanTotal = 0
        running = true
        cancelRequested = false
        awaitingSelection = false
        bytesTotal.set(0)
        activeRequests.set(0)
        logText.setLength(0)
        lastStatus = "Starting…"
        Logx.rotate()
        Logx.append("[run] started, mode parsed")
    }

    fun scanProgress(pages: Int, found: Int) {
        onMain { observer?.onScanProgress(pages, found) }
    }

    fun scanReady(total: Int) {
        scanTotal = total
        awaitingSelection = true
        onMain { observer?.onScanReady(total) }
    }

    fun progress(done: Int, total: Int, status: String) {
        this.done = done
        this.total = total
        lastStatus = status
        onMain { observer?.onProgress(done, total, status) }
    }

    fun stats(bytes: Long, bps: Long, pingMs: Int, threads: Int, etaSec: Int) {
        onMain { observer?.onStats(bytes, bps, pingMs, threads, etaSec) }
    }

    fun log(line: String) {
        logText.append(line).append('\n')
        // Keep the in-memory board small — the full log lives in files/log.txt.
        if (logText.length > 24_000) logText.delete(0, logText.length - 16_000)
        Logx.append(line)
        onMain { observer?.onLog(line) }
    }

    fun finished(ok: Boolean, book: File?) {
        running = false
        awaitingSelection = false
        lastStatus = if (ok) "Done." else "Failed."
        if (book != null) lastBook = book
        onMain { observer?.onDone(ok, book) }
    }
}
