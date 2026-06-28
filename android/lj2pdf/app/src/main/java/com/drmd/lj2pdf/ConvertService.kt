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
import android.os.PowerManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream

/**
 * Foreground service that archives a blog into one book.pdf in the background.
 *
 * Flow (torrent-grabber style — start it and collect the PDF later):
 *   1) SCAN  — walk ?skip=0,step,… from the first page, enumerate the blog
 *              structure (post permalinks, or list pages).
 *   2) CHOOSE — if not fully automatic, report the count and wait for the user
 *              to pick how many (e.g. the freshest 1/3); otherwise take all.
 *   3) DOWNLOAD — render each selected page to PDF (ads stripped, media kept),
 *              then merge into book.pdf with a bookmark TOC.
 *
 * A partial wake lock keeps it running with the screen off (overnight).
 */
class ConvertService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"          // "lj_archive" | "lj_pages" | "list"
        const val EXTRA_AUTO = "auto"          // true = take everything, no prompt
        const val EXTRA_URLS = "urls"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_BASE = "base"
        const val EXTRA_STEP = "step"
        const val EXTRA_FROM = "from"
        const val EXTRA_MAX = "max"
        const val EXTRA_CLEAN = "clean"
        const val EXTRA_NAME = "name"
        const val EXTRA_TREE = "tree"
        const val EXTRA_SELECT_COUNT = "selectCount"
        const val ACTION_STOP = "com.drmd.lj2pdf.STOP"
        const val ACTION_SELECT = "com.drmd.lj2pdf.SELECT"

        private const val CHANNEL = "convert"
        private const val NID = 0x10
        private const val SETTLE_LIST_MS = 1500L
        private const val SETTLE_POST_MS = 2500L   // post pages carry more media
        private const val SCAN_SETTLE_MS = 1200L
        private const val PAGE_TIMEOUT_MS = 90_000L
        private const val DEFAULT_MAX = 2000
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var nm: NotificationManager
    private var web: WebView? = null
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var selection: CompletableDeferred<Int>? = null

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ConvertBus.cancelRequested = true
                selection?.complete(0)
                return START_NOT_STICKY
            }
            ACTION_SELECT -> {
                selection?.complete(intent.getIntExtra(EXTRA_SELECT_COUNT, 0))
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val mode = intent.getStringExtra(EXTRA_MODE) ?: "list"
        val auto = intent.getBooleanExtra(EXTRA_AUTO, true)
        val clean = intent.getBooleanExtra(EXTRA_CLEAN, true)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "book"
        val tree = intent.getStringExtra(EXTRA_TREE)

        running = true
        acquireWakeLock()
        ConvertBus.start(0)
        startForeground(NID, progressNotif("Starting…", 0, 0, true))
        web = WebView(this)

        scope.launch {
            try {
                runJob(intent, mode, auto, clean, name, tree)
            } catch (t: Throwable) {
                ConvertBus.log("[error] ${t.message}")
                finish(false, null)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runJob(
        intent: Intent, mode: String, auto: Boolean, clean: Boolean,
        name: String, tree: String?
    ) {
        val workDir = File(cacheDir, "work").apply { mkdirs() }
        workDir.listFiles()?.forEach { it.delete() }
        val renderer = WebViewPdfRenderer(web!!) { line -> ConvertBus.log(line) }

        // 1) Build the work list (scan for LJ modes, or take the given list).
        val isArchive = mode == "lj_archive"
        val urls = ArrayList<String>()
        val titles = ArrayList<String>()
        when (mode) {
            "lj_archive", "lj_pages" -> {
                val base = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
                val step = intent.getIntExtra(EXTRA_STEP, 20).coerceAtLeast(1)
                val from = intent.getIntExtra(EXTRA_FROM, 1).coerceAtLeast(1)
                val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)
                scanBlog(renderer, base, step, from, max, isArchive, urls, titles)
            }
            else -> {
                urls.addAll(intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf())
                titles.addAll(intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf())
            }
        }

        if (ConvertBus.cancelRequested) { finish(false, null); workDir.deleteRecursively(); return }
        if (urls.isEmpty()) {
            ConvertBus.log("[done] nothing found to download")
            finish(false, null); workDir.deleteRecursively(); return
        }

        // 2) Decide how many to download.
        var count = urls.size
        if (!auto && (mode == "lj_archive" || mode == "lj_pages")) {
            ConvertBus.log("[scan] found $count ${if (isArchive) "post(s)" else "page(s)"} — waiting for your choice")
            nm.notify(NID, progressNotif("Scanned $count — choose how many in the app", 0, 0, true))
            val def = CompletableDeferred<Int>()
            selection = def
            ConvertBus.scanReady(count)
            count = def.await().coerceIn(0, urls.size)
            selection = null
            if (count == 0 || ConvertBus.cancelRequested) {
                ConvertBus.log("[done] cancelled / nothing selected")
                finish(false, null); workDir.deleteRecursively(); return
            }
            ConvertBus.log("[scan] downloading the freshest $count")
        }

        // 3) Download the selected items.
        val pageFiles = ArrayList<File>()
        val outTitles = ArrayList<String>()
        val settle = if (isArchive) SETTLE_POST_MS else SETTLE_LIST_MS
        for (i in 0 until count) {
            if (ConvertBus.cancelRequested) { ConvertBus.log("[info] cancelled"); break }
            val url = urls[i]
            val status = "Saving ${i + 1}/$count"
            ConvertBus.progress(i, count, status)
            nm.notify(NID, progressNotif(status, i, count, false))
            ConvertBus.log("[${i + 1}/$count] $url")

            val f = File(workDir, "page_%04d.pdf".format(i + 1))
            val res = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.render(url, f, settle, clean, requireEntries = false)
            }
            if (res != null && res.ok && f.length() > 0) {
                pageFiles.add(f)
                val t = res.title.ifBlank { titles.getOrElse(i) { "Item ${i + 1}" } }
                outTitles.add(t)
                ConvertBus.log("  ok (${f.length() / 1024} KB)")
            } else {
                ConvertBus.log("  FAILED (skipped)")
            }
        }

        // 4) Merge + export.
        if (pageFiles.isEmpty()) { finish(false, null); workDir.deleteRecursively(); return }
        ConvertBus.progress(count, count, "Merging ${pageFiles.size} page(s)…")
        nm.notify(NID, progressNotif("Merging ${pageFiles.size} page(s)…", 0, 0, true))
        val book = File(getExternalFilesDir(null), "$name.pdf")
        val ok = withContext(Dispatchers.IO) {
            try {
                BookBuilder.mergeWithToc(pageFiles, outTitles, book)
            } catch (t: Throwable) {
                ConvertBus.log("[merge] error: ${t.message}"); false
            }
        }
        workDir.listFiles()?.forEach { it.delete() }
        if (ok && tree != null) copyToTree(book, tree, "$name.pdf")
        finish(ok, if (ok) book else null)
    }

    /** Walk ?skip= from the first page, enumerating posts (archive) or pages. */
    private suspend fun scanBlog(
        renderer: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        archive: Boolean, urls: ArrayList<String>, titles: ArrayList<String>
    ) {
        ConvertBus.log("[scan] scanning blog structure…")
        val seen = HashSet<String>()
        var page = from
        var pageNo = 0
        while (!ConvertBus.cancelRequested && page <= max) {
            val skip = (page - 1) * step
            val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
            nm.notify(NID, progressNotif("Scanning page $page… (${urls.size} found)", 0, 0, true))
            val links = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.collectPostLinks(url, SCAN_SETTLE_MS)
            } ?: emptyList()
            if (links.isEmpty()) { ConvertBus.log("[scan] page $page empty — end of blog"); break }

            val fresh = links.filter { seen.add(it) }
            if (fresh.isEmpty()) { ConvertBus.log("[scan] page $page repeats — stopping"); break }

            if (archive) {
                for (l in fresh) { urls.add(l); titles.add("") }   // title filled at render
            } else {
                pageNo++
                urls.add(url); titles.add("Страница $page")
            }
            ConvertBus.scanProgress(page, urls.size)
            ConvertBus.log("[scan] page $page: +${fresh.size} (total ${urls.size})")
            page++
        }
        if (page > max) ConvertBus.log("[scan] hit the page cap ($max)")
    }

    private fun finish(ok: Boolean, book: File?) {
        running = false
        web?.destroy(); web = null
        releaseWakeLock()
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

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lj2pdf:convert").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)   // up to 6h safety timeout
            }
        } catch (_: Throwable) { /* best effort */ }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
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
            .setContentTitle("Archiving blog → PDF")
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
            .setContentTitle("Archiving failed / cancelled")
            .setContentText("No pages were saved.")
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
        releaseWakeLock()
        web?.destroy(); web = null
        super.onDestroy()
    }
}
