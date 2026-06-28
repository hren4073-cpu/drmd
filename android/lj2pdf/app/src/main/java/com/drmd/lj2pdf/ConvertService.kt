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
 * Foreground service that converts a blog into a single book.pdf in the
 * background, with a progress notification.
 *
 * Two job modes:
 *  - "list"    : a fixed list of URLs (template / single-page modes).
 *  - "lj_auto" : a LiveJournal blog walked from the first page to the LAST one
 *                automatically (?skip=0,step,2*step,…) until a page has no
 *                entries — no need to know the page count up front.
 *
 * Pages are cleaned of ads/promo before rendering when [EXTRA_CLEAN] is set.
 */
class ConvertService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"      // "list" | "lj_auto"
        const val EXTRA_URLS = "urls"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_BASE = "base"      // lj_auto: blog base url
        const val EXTRA_STEP = "step"      // lj_auto: entries per page
        const val EXTRA_FROM = "from"      // lj_auto: start page (1-based)
        const val EXTRA_MAX = "max"        // lj_auto: hard page cap
        const val EXTRA_CLEAN = "clean"
        const val EXTRA_NAME = "name"
        const val EXTRA_TREE = "tree"
        const val ACTION_STOP = "com.drmd.lj2pdf.STOP"

        private const val CHANNEL = "convert"
        private const val NID = 0x10
        private const val SETTLE_MS = 1500L
        private const val PAGE_TIMEOUT_MS = 60_000L
        private const val DEFAULT_MAX = 2000
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
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val mode = intent.getStringExtra(EXTRA_MODE) ?: "list"
        val clean = intent.getBooleanExtra(EXTRA_CLEAN, true)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "book"
        val tree = intent.getStringExtra(EXTRA_TREE)

        running = true
        ConvertBus.start(0)
        startForeground(NID, progressNotif("Starting…", 0, 0, true))
        web = WebView(this)

        scope.launch {
            val workDir = File(cacheDir, "work").apply { mkdirs() }
            workDir.listFiles()?.forEach { it.delete() }
            val renderer = WebViewPdfRenderer(web!!) { line -> ConvertBus.log(line) }
            val pageFiles = ArrayList<File>()
            val titles = ArrayList<String>()

            if (mode == "lj_auto") {
                val base = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
                val step = intent.getIntExtra(EXTRA_STEP, 20).coerceAtLeast(1)
                val from = intent.getIntExtra(EXTRA_FROM, 1).coerceAtLeast(1)
                val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)
                walkBlog(renderer, base, step, from, max, clean, workDir, pageFiles, titles)
            } else {
                val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
                val ts = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
                convertList(renderer, urls, ts, clean, workDir, pageFiles, titles)
            }

            mergeAndFinish(pageFiles, titles, name, tree, workDir)
        }
        return START_NOT_STICKY
    }

    /** Walk a LiveJournal blog from the first page to the last automatically. */
    private suspend fun walkBlog(
        renderer: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        clean: Boolean, workDir: File, pageFiles: ArrayList<File>, titles: ArrayList<String>
    ) {
        var page = from
        var prevSig = ""
        while (!ConvertBus.cancelRequested && page <= max) {
            val skip = (page - 1) * step
            val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
            val status = "Page $page… (${pageFiles.size} saved)"
            ConvertBus.progress(pageFiles.size, pageFiles.size + 1, status)
            nm.notify(NID, progressNotif(status, 0, 0, true))
            ConvertBus.log("[page $page] $url")

            val f = File(workDir, "page_%04d.pdf".format(page))
            val res = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.render(url, f, SETTLE_MS, clean, requireEntries = true)
            }
            if (res == null) { ConvertBus.log("  timeout, stopping"); break }
            if (res.count == 0) { ConvertBus.log("  no entries — reached the last page"); break }
            if (res.signature.isNotEmpty() && res.signature == prevSig) {
                ConvertBus.log("  same entries as previous page — stopping"); break
            }
            prevSig = res.signature
            if (res.ok && f.length() > 0) {
                pageFiles.add(f); titles.add("Страница $page")
                ConvertBus.log("  ok — ${res.count} entries (${f.length() / 1024} KB)")
            } else {
                ConvertBus.log("  render failed, skipped")
            }
            page++
        }
        if (page > max) ConvertBus.log("[info] reached the page cap ($max)")
    }

    private suspend fun convertList(
        renderer: WebViewPdfRenderer, urls: List<String>, srcTitles: List<String>,
        clean: Boolean, workDir: File, pageFiles: ArrayList<File>, titles: ArrayList<String>
    ) {
        for ((i, url) in urls.withIndex()) {
            if (ConvertBus.cancelRequested) { ConvertBus.log("[info] cancelled"); break }
            val status = "Converting ${i + 1}/${urls.size}"
            ConvertBus.progress(i, urls.size, status)
            nm.notify(NID, progressNotif(status, i, urls.size, false))
            ConvertBus.log("[${i + 1}] $url")
            val f = File(workDir, "page_%04d.pdf".format(i + 1))
            val ok = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.renderUrlToPdf(url, f, SETTLE_MS, clean)
            } ?: false
            if (ok && f.length() > 0) {
                pageFiles.add(f); titles.add(srcTitles.getOrElse(i) { "Page ${i + 1}" })
                ConvertBus.log("  ok (${f.length() / 1024} KB)")
            } else {
                ConvertBus.log("  FAILED (skipped)")
            }
        }
    }

    private suspend fun mergeAndFinish(
        pageFiles: ArrayList<File>, titles: ArrayList<String>,
        name: String, tree: String?, workDir: File
    ) {
        if (pageFiles.isEmpty()) { finish(false, null); workDir.deleteRecursively(); return }

        ConvertBus.progress(pageFiles.size, pageFiles.size, "Merging ${pageFiles.size} page(s)…")
        nm.notify(NID, progressNotif("Merging ${pageFiles.size} page(s)…", 0, 0, true))
        val book = File(getExternalFilesDir(null), "$name.pdf")
        val ok = withContext(Dispatchers.IO) {
            try {
                BookBuilder.mergeWithToc(pageFiles, titles, book)
            } catch (t: Throwable) {
                ConvertBus.log("[merge] error: ${t.message}"); false
            }
        }
        workDir.listFiles()?.forEach { it.delete() }
        if (ok && tree != null) copyToTree(book, tree, "$name.pdf")
        finish(ok, if (ok) book else null)
    }

    private fun finish(ok: Boolean, book: File?) {
        running = false
        web?.destroy(); web = null
        stopForeground(true)
        nm.notify(NID + 1, if (ok && book != null) doneNotif(book) else failedNotif())
        ConvertBus.finished(ok, book)
        stopSelf()
    }

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
            val ch = NotificationChannel(CHANNEL, "Conversion", NotificationManager.IMPORTANCE_LOW)
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

    private fun doneNotif(book: File): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PDF book ready")
            .setContentText("${book.name} (${book.length() / 1024} KB) — tap to open")
            .setAutoCancel(true)
            .setContentIntent(openIntent(book))
            .build()

    private fun failedNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PDF book failed")
            .setContentText("No pages were converted.")
            .setAutoCancel(true)
            .build()

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
