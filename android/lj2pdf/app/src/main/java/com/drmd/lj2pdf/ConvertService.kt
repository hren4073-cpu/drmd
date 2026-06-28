package com.drmd.lj2pdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream

/**
 * Foreground service that converts a list of URLs into a single book.pdf in
 * the background, showing a progress notification. The final notification
 * opens the finished book. Mode-agnostic: it just renders the URLs it is
 * given (the Activity builds the list per the chosen mode).
 */
class ConvertService : Service() {

    companion object {
        const val EXTRA_URLS = "urls"      // ArrayList<String>
        const val EXTRA_TITLES = "titles"  // ArrayList<String>
        const val EXTRA_NAME = "name"      // output base name (no extension)
        const val EXTRA_TREE = "tree"      // SAF tree uri (optional)
        const val ACTION_STOP = "com.drmd.lj2pdf.STOP"

        private const val CHANNEL = "convert"
        private const val NID = 0x10
        private const val SETTLE_MS = 1500L
        private const val PAGE_TIMEOUT_MS = 45_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var nm: NotificationManager
    private var web: WebView? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ConvertBus.cancelRequested = true
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val urls = intent?.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val titles = intent?.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "book"
        val tree = intent?.getStringExtra(EXTRA_TREE)
        if (urls.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        ConvertBus.start(urls.size)
        startForeground(NID, progressNotif("Starting…", 0, urls.size, true))
        web = WebView(this)
        scope.launch { runPipeline(urls, titles, name, tree) }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(
        urls: List<String>, titles: List<String>, name: String, tree: String?
    ) {
        val workDir = File(cacheDir, "work").apply { mkdirs() }
        workDir.listFiles()?.forEach { it.delete() }

        val renderer = WebViewPdfRenderer(web!!) { line -> ConvertBus.log(line) }
        val pageFiles = ArrayList<File>()
        val pageTitles = ArrayList<String>()

        for ((i, url) in urls.withIndex()) {
            if (ConvertBus.cancelRequested) { ConvertBus.log("[info] cancelled"); break }
            val status = "Converting ${i + 1}/${urls.size}"
            ConvertBus.progress(i, urls.size, status)
            nm.notify(NID, progressNotif(status, i, urls.size, false))
            ConvertBus.log("[${i + 1}] $url")

            val f = File(workDir, "page_%04d.pdf".format(i + 1))
            val ok = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.renderUrlToPdf(url, f, SETTLE_MS)
            } ?: false
            if (ok && f.length() > 0) {
                pageFiles.add(f)
                pageTitles.add(titles.getOrElse(i) { "Page ${i + 1}" })
                ConvertBus.log("  ok (${f.length() / 1024} KB)")
            } else {
                ConvertBus.log("  FAILED (skipped)")
            }
        }

        if (pageFiles.isEmpty()) {
            finish(false, null, name, null)
            return
        }

        ConvertBus.progress(urls.size, urls.size, "Merging ${pageFiles.size} page(s)…")
        nm.notify(NID, progressNotif("Merging…", 0, 0, true))
        val book = File(getExternalFilesDir(null), "$name.pdf")
        val ok = withContext(Dispatchers.IO) {
            try {
                BookBuilder.mergeWithToc(pageFiles, pageTitles, book)
            } catch (t: Throwable) {
                ConvertBus.log("[merge] error: ${t.message}")
                false
            }
        }
        workDir.listFiles()?.forEach { it.delete() }

        if (ok && tree != null) {
            copyToTree(book, tree, "$name.pdf")
        }
        finish(ok, if (ok) book else null, name, tree)
    }

    private fun finish(ok: Boolean, book: File?, name: String, tree: String?) {
        running = false
        web?.destroy(); web = null
        stopForeground(true)
        nm.notify(NID + 1, if (ok && book != null) doneNotif(book) else failedNotif())
        ConvertBus.finished(ok, book)
        stopSelf()
    }

    /** Copy the finished book into the user-chosen SAF folder. */
    private fun copyToTree(src: File, treeUri: String, displayName: String) {
        try {
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeUri)) ?: return
            tree.findFile(displayName)?.delete()
            val doc = tree.createFile("application/pdf", displayName) ?: return
            contentResolver.openOutputStream(doc.uri)?.use { out ->
                FileInputStream(src).use { it.copyTo(out) }
            }
            ConvertBus.log("[export] copied to the chosen folder")
        } catch (t: Throwable) {
            ConvertBus.log("[export] failed: ${t.message}")
        }
    }

    // -- notifications -----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "Conversion", NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun progressNotif(
        status: String, done: Int, total: Int, indeterminate: Boolean
    ): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Building PDF book")
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (indeterminate) b.setProgress(0, 0, true) else b.setProgress(maxOf(total, 1), done, false)
        return b.build()
    }

    private fun doneNotif(book: File): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PDF book ready")
            .setContentText("${book.name} (${book.length() / 1024} KB) — tap to open")
            .setAutoCancel(true)
            .setContentIntent(openIntent(book))
            .build()
    }

    private fun failedNotif(): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PDF book failed")
            .setContentText("No pages were converted.")
            .setAutoCancel(true)
            .build()
    }

    private fun openIntent(book: File): PendingIntent {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", book)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, "Open book.pdf")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, chooser, flags)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        web?.destroy(); web = null
        super.onDestroy()
    }
}
