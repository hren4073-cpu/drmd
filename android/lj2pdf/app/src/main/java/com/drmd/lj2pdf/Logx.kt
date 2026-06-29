package com.drmd.lj2pdf

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny persistent logger. Every line is appended to files/log.txt and flushed
 * immediately, so even a native crash (e.g. OOM) leaves the last step on disk —
 * the "View log" screen then shows exactly where it died.
 */
object Logx {
    private var file: File? = null
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(f: File) {
        file = f
        rotate()
    }

    /** Truncate if the log grew large (keep it from filling storage). */
    fun rotate(maxBytes: Long = 1_000_000) {
        val f = file ?: return
        try { if (f.exists() && f.length() > maxBytes) f.writeText("") } catch (_: Throwable) {}
    }

    @Synchronized
    fun append(line: String) {
        val f = file ?: return
        try { f.appendText("${ts.format(Date())}  $line\n") } catch (_: Throwable) {}
    }

    fun read(): String = try { file?.readText() ?: "" } catch (_: Throwable) { "" }

    fun clear() { try { file?.writeText("") } catch (_: Throwable) {} }
}
