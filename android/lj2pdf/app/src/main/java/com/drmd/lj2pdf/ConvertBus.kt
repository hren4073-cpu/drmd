package com.drmd.lj2pdf

import java.io.File

/**
 * Tiny in-process bus between [ConvertService] (producer) and [MainActivity]
 * (observer). Same process, so a plain singleton is enough — no broadcasts.
 * All callbacks fire on the main thread.
 */
object ConvertBus {
    interface Observer {
        fun onScanProgress(pagesScanned: Int, itemsFound: Int)
        /** Scan finished and the service is waiting for the user to pick a count. */
        fun onScanReady(total: Int)
        fun onProgress(done: Int, total: Int, status: String)
        fun onLog(line: String)
        fun onDone(ok: Boolean, book: File?)
    }

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
        logText.setLength(0)
        lastStatus = "Starting…"
        Logx.rotate()
        Logx.append("[run] started, mode parsed")
    }

    fun scanProgress(pages: Int, found: Int) {
        observer?.onScanProgress(pages, found)
    }

    fun scanReady(total: Int) {
        scanTotal = total
        awaitingSelection = true
        observer?.onScanReady(total)
    }

    fun progress(done: Int, total: Int, status: String) {
        this.done = done
        this.total = total
        lastStatus = status
        observer?.onProgress(done, total, status)
    }

    fun log(line: String) {
        logText.append(line).append('\n')
        Logx.append(line)
        observer?.onLog(line)
    }

    fun finished(ok: Boolean, book: File?) {
        running = false
        awaitingSelection = false
        lastStatus = if (ok) "Done." else "Failed."
        if (book != null) lastBook = book
        observer?.onDone(ok, book)
    }
}
