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
 * Project model (mode "lj_project"): each blog is a persistent project with its
 * own folder and per-post PDFs. The first run scans the blog, lets the user
 * pick how much to save, renders those posts and merges them. A later run on
 * the same blog is an UPDATE: it scans only the top until it meets an already
 * archived post, renders just the newly published posts and re-merges (reusing
 * existing per-post PDFs) — like refreshing a torrent.
 *
 * Also supports one-off "lj_pages" (list pages) and "list" (template/single).
 */
class ConvertService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"          // "lj_project" | "lj_pages" | "list"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_DEEP = "deep"
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
        private const val SETTLE_POST_MS = 2500L
        private const val SCAN_SETTLE_MS = 1200L
        private const val PAGE_TIMEOUT_MS = 90_000L
        private const val DEFAULT_MAX = 2000
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var nm: NotificationManager
    private var renderer: WebViewPdfRenderer? = null
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
            ACTION_STOP -> { ConvertBus.cancelRequested = true; selection?.complete(0); return START_NOT_STICKY }
            ACTION_SELECT -> { selection?.complete(intent.getIntExtra(EXTRA_SELECT_COUNT, 0)); return START_NOT_STICKY }
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

        scope.launch {
            try {
                val r = WebViewPdfRenderer(this@ConvertService) { line -> ConvertBus.log(line) }
                renderer = r
                when (mode) {
                    "lj_project" -> runProject(r, intent, clean, tree)
                    else -> runSimple(r, intent, mode, auto, clean, name, tree)
                }
            } catch (t: Throwable) {
                ConvertBus.log("[error] ${t.message}")
                finish(false, null)
            }
        }
        // If the OS kills us mid-run, redeliver the same intent and resume:
        // project mode skips posts already on disk, so it picks up where it left off.
        return START_REDELIVER_INTENT
    }

    // ===================== PROJECT (archive + update) =====================

    private suspend fun runProject(
        renderer: WebViewPdfRenderer, intent: Intent, clean: Boolean, tree: String?
    ) {
        val baseIn = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
        if (baseIn.isEmpty()) { finish(false, null); return }
        val project = Projects.forBase(this, baseIn)
        val existing = project.entries()
        val knownIds = existing.map { it.id }.toSet()
        val auto = intent.getBooleanExtra(EXTRA_AUTO, true)
        val deep = intent.getBooleanExtra(EXTRA_DEEP, false)
        val from = intent.getIntExtra(EXTRA_FROM, 1).coerceAtLeast(1)
        val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)
        val step = if (existing.isNotEmpty()) project.step
                   else intent.getIntExtra(EXTRA_STEP, 20).coerceAtLeast(1).also { project.step = it }

        val newEntries = ArrayList<PostEntry>()

        if (existing.isNotEmpty() && deep) {
            // ---- DEEP RESCAN: full archive walk to catch backdated/missed posts ----
            ConvertBus.log("[deep] full re-scan of ${project.name} for backdated/missed posts…")
            val scanned = scanArchive(renderer, project.base, step, from, max)
            if (ConvertBus.cancelRequested || scanned.isEmpty()) { finish(false, null); return }
            val titleById = HashMap<String, String>()
            existing.forEach { titleById[it.id] = it.title }
            val toRender = scanned.filter { Projects.idOf(it) !in knownIds }
            ConvertBus.log("[deep] ${toRender.size} new/backdated post(s) to fetch")
            renderPosts(renderer, project, toRender, clean, newEntries)
            newEntries.forEach { titleById[it.id] = it.title }
            // Rebuild the index in full archive order (places backdated posts right).
            val finalEntries = scanned.mapNotNull { perma ->
                val id = Projects.idOf(perma)
                val f = project.postPdf(id)
                if (f.exists() && f.length() > 0) {
                    PostEntry(id, perma, titleById[id] ?: "Пост $id")
                } else null
            }
            val ok = mergeProject(project, finalEntries, tree)
            ConvertBus.log("[deep] added ${newEntries.size}, total ${finalEntries.size}")
            finish(ok, if (ok) project.bookFile else null)
        } else if (existing.isNotEmpty()) {
            // ---- UPDATE (fast: only the new top of the feed) ----
            ConvertBus.log("[update] checking ${project.name} for new posts…")
            val fresh = scanNewPosts(renderer, project.base, step, from, max, knownIds)
            if (ConvertBus.cancelRequested) { finish(false, null); return }
            if (fresh.isEmpty()) {
                ConvertBus.log("[update] already up to date")
                if (project.bookFile.exists()) { finish(true, project.bookFile); return }
                val ok = mergeProject(project, existing, tree)
                finish(ok, project.bookFile.takeIf { it.exists() }); return
            }
            ConvertBus.log("[update] ${fresh.size} new post(s)")
            renderPosts(renderer, project, fresh, clean, newEntries)
            val merged = newEntries + existing
            val ok = mergeProject(project, merged, tree)
            ConvertBus.log("[update] added ${newEntries.size}, total ${merged.size}")
            finish(ok, if (ok) project.bookFile else null)
        } else {
            // ---- FRESH ----
            val scanned = scanArchive(renderer, project.base, step, from, max)
            if (ConvertBus.cancelRequested || scanned.isEmpty()) {
                ConvertBus.log("[done] nothing found"); finish(false, null); return
            }
            var count = scanned.size
            if (!auto) {
                val def = CompletableDeferred<Int>()
                selection = def
                ConvertBus.log("[scan] found $count post(s) — waiting for your choice")
                nm.notify(NID, progressNotif("Scanned $count — choose how many in the app", 0, 0, true))
                ConvertBus.scanReady(count)
                count = def.await().coerceIn(0, scanned.size); selection = null
                if (count == 0 || ConvertBus.cancelRequested) { finish(false, null); return }
            }
            renderPosts(renderer, project, scanned.take(count), clean, newEntries)
            val ok = mergeProject(project, newEntries, tree)
            finish(ok, if (ok) project.bookFile else null)
        }
    }

    /** Walk the whole blog, collecting every post permalink (newest first). */
    private suspend fun scanAllPosts(
        renderer: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int
    ): List<String> {
        ConvertBus.log("[scan] scanning blog structure…")
        val out = ArrayList<String>(); val seen = HashSet<String>()
        var page = from
        while (!ConvertBus.cancelRequested && page <= max) {
            val skip = (page - 1) * step
            val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
            nm.notify(NID, progressNotif("Scanning page $page… (${out.size} posts)", 0, 0, true))
            val links = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.collectPostLinks(url, SCAN_SETTLE_MS)
            } ?: emptyList()
            if (links.isEmpty()) { ConvertBus.log("[scan] page $page empty — end of blog"); break }
            val fresh = links.filter { seen.add(it) }
            if (fresh.isEmpty()) { ConvertBus.log("[scan] page $page repeats — stopping"); break }
            out.addAll(fresh)
            ConvertBus.scanProgress(page, out.size)
            page++
        }
        return out
    }

    /**
     * Full-archive scan: probe every year page /YYYY/ to collect all month
     * links, then crawl each month /YYYY/MM/ (descending into days if a month
     * page is day-grouped) for post permalinks. LiveJournal's ?skip= is capped
     * and /calendar only lists the current year, so probing years is what
     * reaches the whole blog. Falls back to ?skip= if no months are found.
     */
    private suspend fun scanArchive(
        renderer: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int
    ): List<String> {
        val monthRe = Regex("^https?://[^/]+/(\\d{4})/(\\d{2})/?$")
        val dayRe = Regex("^https?://[^/]+/(\\d{4})/(\\d{2})/(\\d{2})/?$")
        val yearRe = Regex("^https?://[^/]+/(\\d{4})/?$")
        val monthSet = LinkedHashSet<String>()

        // 1) read the calendar: month links (latest year) + a year navigation
        //    that also lists years posts were BACK-DATED to (e.g. 1965).
        ConvertBus.log("[scan] reading archive…")
        val cal = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            renderer.collectAllLinks("$base/calendar", SCAN_SETTLE_MS)
        } ?: emptyList()
        cal.filter { monthRe.matches(it) }.forEach { monthSet.add(it) }

        // 2) years to probe = every year the calendar links to (catches back-
        //    dated years) + a blind range down to 1940 (LJ users back-date posts
        //    to the mid-1900s, below the 1999 LJ epoch, e.g. 1950–1970).
        val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = sortedSetOf(Comparator.reverseOrder<Int>())
        cal.filter { yearRe.matches(it) }.forEach { val y = yr(it); if (y in 1900..curYear) years.add(y) }
        for (y in curYear downTo 1940) years.add(y)

        // 3) probe each year page /YYYY/ for its month links.
        var probed = 0
        for (y in years) {
            if (ConvertBus.cancelRequested) break
            probed++
            nm.notify(NID, progressNotif(
                "Scanning archive: year $y ($probed/${years.size})… (${monthSet.size} months)",
                0, 0, true))
            (withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.collectAllLinks("$base/$y/", SCAN_SETTLE_MS)
            } ?: emptyList()).filter { monthRe.matches(it) }.forEach { monthSet.add(it) }
        }

        val months = monthSet.distinct().sortedByDescending { ym(it) }
        if (months.isEmpty()) {
            ConvertBus.log("[scan] no archive months — using ?skip= (may be limited)")
            return scanAllPosts(renderer, base, step, from, max)
        }
        ConvertBus.log("[scan] archive has ${months.size} month(s)")
        val out = ArrayList<String>(); val seen = HashSet<String>()
        var done = 0
        for (m in months) {
            if (ConvertBus.cancelRequested) break
            done++
            nm.notify(NID, progressNotif(
                "Scanning archive $done/${months.size}… (${out.size} posts)", done, months.size, false))
            val posts = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.collectPostLinks(m, SCAN_SETTLE_MS)
            } ?: emptyList()
            if (posts.isEmpty()) {
                // Month page is day-grouped — descend into each day.
                val days = (withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    renderer.collectAllLinks(m, SCAN_SETTLE_MS)
                } ?: emptyList()).filter { dayRe.matches(it) }.distinct().sortedByDescending { ymd(it) }
                for (d in days) {
                    if (ConvertBus.cancelRequested) break
                    val dp = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                        renderer.collectPostLinks(d, SCAN_SETTLE_MS)
                    } ?: emptyList()
                    for (l in dp) if (seen.add(l)) out.add(l)
                }
            } else {
                for (l in posts) if (seen.add(l)) out.add(l)
            }
            ConvertBus.scanProgress(done, out.size)
        }
        ConvertBus.log("[scan] archive total: ${out.size} post(s)")
        return out
    }

    private fun yr(url: String): Int =
        Regex("/(\\d{4})").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun ym(url: String): Int {
        val m = Regex("/(\\d{4})/(\\d{2})").find(url) ?: return 0
        return m.groupValues[1].toInt() * 100 + m.groupValues[2].toInt()
    }

    private fun ymd(url: String): Int {
        val m = Regex("/(\\d{4})/(\\d{2})/(\\d{2})").find(url) ?: return 0
        return (m.groupValues[1].toInt() * 100 + m.groupValues[2].toInt()) * 100 +
            m.groupValues[3].toInt()
    }

    /** Walk only the top of the blog until an already-archived post is met. */
    private suspend fun scanNewPosts(
        renderer: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        knownIds: Set<String>
    ): List<String> {
        val out = ArrayList<String>(); val seen = HashSet<String>()
        var page = from
        while (!ConvertBus.cancelRequested && page <= max) {
            val skip = (page - 1) * step
            val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
            nm.notify(NID, progressNotif("Checking page $page… (${out.size} new)", 0, 0, true))
            val links = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.collectPostLinks(url, SCAN_SETTLE_MS)
            } ?: emptyList()
            if (links.isEmpty()) break
            var hitKnown = false
            for (l in links) {
                if (Projects.idOf(l) in knownIds) { hitKnown = true; break }
                if (seen.add(l)) out.add(l)
            }
            if (hitKnown) break
            ConvertBus.scanProgress(page, out.size)
            page++
        }
        return out
    }

    /** Render each permalink to its persistent posts/<id>.pdf (skip if present). */
    private suspend fun renderPosts(
        renderer: WebViewPdfRenderer, project: Project, permalinks: List<String>,
        clean: Boolean, out: ArrayList<PostEntry>
    ) {
        val total = permalinks.size
        for ((i, perma) in permalinks.withIndex()) {
            if (ConvertBus.cancelRequested) { ConvertBus.log("[info] cancelled"); break }
            val id = Projects.idOf(perma)
            val status = "Saving post ${i + 1}/$total"
            ConvertBus.progress(i, total, status)
            nm.notify(NID, progressNotif(status, i, total, false))
            ConvertBus.log("[${i + 1}/$total] $perma")

            val pdf = project.postPdf(id)
            if (pdf.exists() && pdf.length() > 0) {
                out.add(PostEntry(id, perma, "Пост $id")); continue
            }
            val res = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.render(perma, pdf, SETTLE_POST_MS, clean, requireEntries = false)
            }
            if (res != null && res.ok && pdf.length() > 0) {
                out.add(PostEntry(id, perma, res.title.ifBlank { "Пост $id" }))
                ConvertBus.log("  ok (${pdf.length() / 1024} KB)")
            } else {
                ConvertBus.log("  FAILED (skipped)")
            }
        }
    }

    /** Save the index and merge all present per-post PDFs into book.pdf. */
    private suspend fun mergeProject(
        project: Project, entries: List<PostEntry>, tree: String?
    ): Boolean {
        val valid = entries.filter { project.postPdf(it.id).let { f -> f.exists() && f.length() > 0 } }
        if (valid.isEmpty()) return false
        project.saveEntries(valid)
        ConvertBus.progress(valid.size, valid.size, "Merging ${valid.size} post(s)…")
        nm.notify(NID, progressNotif("Merging ${valid.size} post(s)…", 0, 0, true))
        val files = valid.map { project.postPdf(it.id) }
        val titles = valid.map { it.title }
        val ok = withContext(Dispatchers.IO) {
            try { BookBuilder.mergeWithToc(files, titles, project.bookFile) }
            catch (t: Throwable) { ConvertBus.log("[merge] error: ${t.message}"); false }
        }
        if (ok && tree != null) copyToTree(project.bookFile, tree, "${project.name}.pdf")
        return ok
    }

    // ===================== SIMPLE (pages / list) ==========================

    private suspend fun runSimple(
        renderer: WebViewPdfRenderer, intent: Intent, mode: String,
        auto: Boolean, clean: Boolean, name: String, tree: String?
    ) {
        val workDir = File(cacheDir, "work").apply { mkdirs() }
        workDir.listFiles()?.forEach { it.delete() }

        val urls = ArrayList<String>()
        val titles = ArrayList<String>()
        if (mode == "lj_pages") {
            val base = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
            val step = intent.getIntExtra(EXTRA_STEP, 20).coerceAtLeast(1)
            val from = intent.getIntExtra(EXTRA_FROM, 1).coerceAtLeast(1)
            val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)
            var page = from
            while (!ConvertBus.cancelRequested && page <= max) {
                val skip = (page - 1) * step
                val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
                nm.notify(NID, progressNotif("Scanning page $page…", 0, 0, true))
                val links = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    renderer.collectPostLinks(url, SCAN_SETTLE_MS)
                } ?: emptyList()
                if (links.isEmpty()) break
                urls.add(url); titles.add("Страница $page")
                ConvertBus.scanProgress(page, urls.size)
                page++
            }
        } else {
            urls.addAll(intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf())
            titles.addAll(intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf())
        }

        if (ConvertBus.cancelRequested || urls.isEmpty()) {
            ConvertBus.log("[done] nothing to convert"); finish(false, null)
            workDir.deleteRecursively(); return
        }

        var count = urls.size
        if (!auto && mode == "lj_pages") {
            val def = CompletableDeferred<Int>()
            selection = def
            nm.notify(NID, progressNotif("Scanned $count — choose how many in the app", 0, 0, true))
            ConvertBus.scanReady(count)
            count = def.await().coerceIn(0, urls.size); selection = null
            if (count == 0 || ConvertBus.cancelRequested) { finish(false, null); workDir.deleteRecursively(); return }
        }

        val pageFiles = ArrayList<File>(); val outTitles = ArrayList<String>()
        for (i in 0 until count) {
            if (ConvertBus.cancelRequested) { ConvertBus.log("[info] cancelled"); break }
            val url = urls[i]
            val status = "Saving ${i + 1}/$count"
            ConvertBus.progress(i, count, status)
            nm.notify(NID, progressNotif(status, i, count, false))
            ConvertBus.log("[${i + 1}/$count] $url")
            val f = File(workDir, "page_%04d.pdf".format(i + 1))
            val ok = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.renderUrlToPdf(url, f, SETTLE_LIST_MS, clean)
            } ?: false
            if (ok && f.length() > 0) {
                pageFiles.add(f); outTitles.add(titles.getOrElse(i) { "Item ${i + 1}" })
                ConvertBus.log("  ok (${f.length() / 1024} KB)")
            } else ConvertBus.log("  FAILED (skipped)")
        }

        if (pageFiles.isEmpty()) { finish(false, null); workDir.deleteRecursively(); return }
        ConvertBus.progress(count, count, "Merging ${pageFiles.size} page(s)…")
        nm.notify(NID, progressNotif("Merging ${pageFiles.size} page(s)…", 0, 0, true))
        val book = File(getExternalFilesDir(null), "$name.pdf")
        val ok = withContext(Dispatchers.IO) {
            try { BookBuilder.mergeWithToc(pageFiles, outTitles, book) }
            catch (t: Throwable) { ConvertBus.log("[merge] error: ${t.message}"); false }
        }
        workDir.listFiles()?.forEach { it.delete() }
        if (ok && tree != null) copyToTree(book, tree, "$name.pdf")
        finish(ok, if (ok) book else null)
    }

    // ===================== shared ========================================

    private fun finish(ok: Boolean, book: File?) {
        running = false
        renderer?.destroy(); renderer = null
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
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

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
        renderer?.destroy(); renderer = null
        super.onDestroy()
    }
}
