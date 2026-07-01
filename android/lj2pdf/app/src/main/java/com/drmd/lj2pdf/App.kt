package com.drmd.lj2pdf

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures any uncaught exception to files/crash.txt so the next app launch can
 * show the exact stack trace (handy when the only symptom is "it crashed").
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Logx.init(File(filesDir, "log.txt"))
        Logx.append("[app] started")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("thread: ${thread.name}\n\n$sw")
                Logx.append("[CRASH] ${thread.name}: $sw")
            } catch (_: Throwable) { /* ignore */ }
            prev?.uncaughtException(thread, ex)
        }
    }
}
