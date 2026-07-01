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
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.InetSocketAddress
import java.net.Socket
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
        const val EXTRA_RAG_DIRS = "ragDirs"   // ArrayList<String> of project dirs
        const val EXTRA_TG_TREE = "tgTree"     // SAF tree of a Telegram export
        const val EXTRA_RAG_CHUNK = "ragChunk"
        const val EXTRA_RAG_OVERLAP = "ragOverlap"
        const val EXTRA_AUTO_RAG = "autoRag"   // also export RAG right after archiving
        const val EXTRA_FORMAT = "format"      // "pdf" | "epub"
        const val EXTRA_PDF_FONT = "pdfFont"   // body font size (pt)
        const val EXTRA_PDF_VOLUME = "pdfVolume" // posts per том
        const val EXTRA_EPUB_FONT = "epubFont"
        const val EXTRA_DL_THREADS = "dlThreads"     // parallel HTTP downloads
        const val EXTRA_CPU_THREADS = "cpuThreads"   // parallel PDF/EPUB build
        const val EXTRA_CONN_TIMEOUT = "connTimeout" // ms
        const val EXTRA_IMG_ON = "imgOn"             // download images?
        const val EXTRA_IMG_MAX = "imgMax"           // max image dimension px
        private const val RAG_CHUNK = 1000
        private const val RAG_OVERLAP = 150
        // Split big blogs: 100 posts = 1 PDF volume, so each merge only loads
        // ~100 posts into memory at a time (keeps peak RAM low on the phone).
        private const val VOLUME_SIZE = 100
        const val ACTION_STOP = "com.drmd.lj2pdf.STOP"
        const val ACTION_SELECT = "com.drmd.lj2pdf.SELECT"

        private const val CHANNEL = "convert"
        private const val NID = 0x10
        private const val SETTLE_LIST_MS = 900L
        private const val SETTLE_POST_MS = 2500L   // posts need images loaded
        private const val SCAN_SETTLE_MS = 600L    // scanning only collects links
        private const val PAGE_TIMEOUT_MS = 90_000L
        private const val DEFAULT_MAX = 2000
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var nm: NotificationManager
    private var renderer: WebViewPdfRenderer? = null
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sampler: Job? = null
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

        // Apply network settings (threads + timeout) before anything fetches.
        val dlThreads = intent.getIntExtra(EXTRA_DL_THREADS, 8).coerceIn(1, 32)
        val connTimeout = intent.getIntExtra(EXTRA_CONN_TIMEOUT, 25_000).toLong()
        Http.configure(dlThreads, connTimeout)

        running = true
        acquireWakeLock()
        acquireWifiLock()
        ConvertBus.start(0)
        startForeground(NID, progressNotif("Starting…", 0, 0, true))
        startSampler(intent.getStringExtra(EXTRA_BASE)?.let { Uri.parse(it).host })

        scope.launch {
            try {
                if (mode == "rag") { runRag(intent, name, tree); return@launch }
                if (mode == "tg_rag") { runTgRag(intent, name, tree); return@launch }
                if (mode == "download") { runDownload(intent); return@launch }
                if (mode == "build_pdf") { runBuildPdf(intent, tree); return@launch }
                if (mode == "build_epub") { runBuildEpub(intent, tree); return@launch }
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

    // ===================== STAGE 1: download HTML base ====================

    /**
     * Multithreaded HTML download (no WebView). Scans the blog over HTTP+jsoup,
     * downloads each post's HTML + images in parallel, and saves the index.
     * The PDF / EPUB / RAG builders later read this saved base.
     */
    private suspend fun runDownload(intent: Intent) {
        val baseIn = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
        if (baseIn.isEmpty()) { finish(false, null); return }
        val project = Projects.forBase(this, baseIn)
        val existing = project.entries()
        val knownIds = existing.map { it.id }.toSet()
        val auto = intent.getBooleanExtra(EXTRA_AUTO, true)
        val deep = intent.getBooleanExtra(EXTRA_DEEP, false)
        val step = if (existing.isNotEmpty()) project.step else intent.getIntExtra(EXTRA_STEP, 20)
        if (existing.isEmpty()) project.step = step
        val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)
        val par = intent.getIntExtra(EXTRA_DL_THREADS, 8)
        val note: (String) -> Unit = { s -> nm.notify(NID, progressNotif(s, 0, 0, true)) }

        ConvertBus.log("[download] $baseIn")
        val scanned = if (existing.isNotEmpty() && !deep)
            SiteScan.scanNew(project.base, step, knownIds, max, par, note)
        else
            SiteScan.scanAll(project.base, max, par, note)
        if (ConvertBus.cancelRequested) { finish(false, null); return }
        if (scanned.isEmpty() && existing.isEmpty()) {
            ConvertBus.log("[download] nothing found"); finish(false, null); return
        }

        // Fresh blog + not auto → let the user pick how many to grab.
        var toGet = scanned
        if (existing.isEmpty() && !auto && scanned.isNotEmpty()) {
            val def = CompletableDeferred<Int>()
            selection = def
            ConvertBus.log("[scan] found ${scanned.size} post(s) — waiting for your choice")
            nm.notify(NID, progressNotif("Scanned ${scanned.size} — choose how many", 0, 0, true))
            ConvertBus.scanReady(scanned.size)
            val count = def.await().coerceIn(0, scanned.size); selection = null
            if (count == 0 || ConvertBus.cancelRequested) { finish(false, null); return }
            toGet = scanned.take(count)
        }

        val dlThreads = intent.getIntExtra(EXTRA_DL_THREADS, 8)
        val imgOn = intent.getBooleanExtra(EXTRA_IMG_ON, true)
        val imgMax = intent.getIntExtra(EXTRA_IMG_MAX, 0)
        val fresh = HtmlArchiver.download(project, toGet, dlThreads, imgOn, imgMax)
        // Rebuild the index: full/deep scans use archive order; updates prepend.
        val byId = (fresh + existing).associateBy { it.id }
        val finalEntries = if (existing.isEmpty() || deep)
            scanned.mapNotNull { byId[Projects.idOf(it)] }.ifEmpty { byId.values.toList() }
        else
            fresh + existing.filter { it.id !in fresh.map { f -> f.id }.toSet() }
        project.saveEntries(finalEntries)
        ConvertBus.log("[download] HTML base ready: ${finalEntries.size} post(s)")
        finish(true, null)
    }

    // ===================== STAGE 2: build PDF from the base ===============

    private suspend fun runBuildPdf(intent: Intent, tree: String?) {
        val baseIn = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
        if (baseIn.isEmpty()) { finish(false, null); return }
        val project = Projects.forBase(this, baseIn)
        val entries = project.entries()
        if (entries.isEmpty()) {
            ConvertBus.log("[pdf] no HTML base — download first"); finish(false, null); return
        }
        val fontSize = intent.getIntExtra(EXTRA_PDF_FONT, 14).coerceIn(8, 32).toFloat()
        val volume = intent.getIntExtra(EXTRA_PDF_VOLUME, VOLUME_SIZE).coerceIn(10, 500)
        val cpu = intent.getIntExtra(EXTRA_CPU_THREADS, 0)
        val rendered = PdfRenderer2.renderAll(project, entries, fontSize, cpu)
        if (rendered.isEmpty() || ConvertBus.cancelRequested) { finish(false, null); return }
        val book = mergeProject(project, rendered, tree, volume)
        finish(book != null, book)
    }

    // ===================== STAGE 2: build EPUB from the base ==============

    private suspend fun runBuildEpub(intent: Intent, tree: String?) {
        val baseIn = (intent.getStringExtra(EXTRA_BASE) ?: "").trimEnd('/')
        if (baseIn.isEmpty()) { finish(false, null); return }
        val project = Projects.forBase(this, baseIn)
        val entries = project.entries()
        if (entries.isEmpty()) {
            ConvertBus.log("[epub] no HTML base — download first"); finish(false, null); return
        }
        val font = intent.getIntExtra(EXTRA_EPUB_FONT, 18).coerceIn(10, 32)
        val cpu = intent.getIntExtra(EXTRA_CPU_THREADS, 0)
        ConvertBus.progress(0, entries.size, "Building EPUB…")
        nm.notify(NID, progressNotif("Building EPUB…", 0, 0, true))
        val out = project.epubFile
        val ok = withContext(Dispatchers.IO) {
            try { EpubBuilder.build(project, entries, out, font, cpu) }
            catch (t: Throwable) { ConvertBus.log("[epub] error: ${t.message}"); false }
        }
        if (ok && tree != null) copyToTree(out, tree, "${project.name}.epub", "application/epub+zip")
        finish(ok, if (ok) out else null)
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
        // Platform profile (null = built-in LiveJournal engine below).
        val profile = Profiles.forBase(project.base)
        val idOf: (String) -> String = { u -> profile?.idOf(u) ?: Projects.idOf(u) }
        val note: (String) -> Unit = { s -> nm.notify(NID, progressNotif(s, 0, 0, true)) }
        if (profile != null) ConvertBus.log("[scan] platform: ${profile.key}")
        val auto = intent.getBooleanExtra(EXTRA_AUTO, true)
        val deep = intent.getBooleanExtra(EXTRA_DEEP, false)
        val from = intent.getIntExtra(EXTRA_FROM, 1).coerceAtLeast(1)
        val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)
        val step = if (existing.isNotEmpty()) project.step
                   else intent.getIntExtra(EXTRA_STEP, 20).coerceAtLeast(1).also { project.step = it }
        val autoRag = intent.getBooleanExtra(EXTRA_AUTO_RAG, false)
        val ragChunk = intent.getIntExtra(EXTRA_RAG_CHUNK, RAG_CHUNK)
        val ragOverlap = intent.getIntExtra(EXTRA_RAG_OVERLAP, RAG_OVERLAP)

        val newEntries = ArrayList<PostEntry>()

        if (existing.isNotEmpty() && deep) {
            // ---- DEEP RESCAN: full archive walk to catch backdated/missed posts ----
            ConvertBus.log("[deep] full re-scan of ${project.name} for backdated/missed posts…")
            val scanned = profile?.scanAll(renderer, project.base, step, from, max, note)
                ?: scanArchive(renderer, project.base, step, from, max)
            if (ConvertBus.cancelRequested || scanned.isEmpty()) { finish(false, null); return }
            val titleById = HashMap<String, String>()
            existing.forEach { titleById[it.id] = it.title }
            val toRender = scanned.filter { idOf(it) !in knownIds }
            ConvertBus.log("[deep] ${toRender.size} new/backdated post(s) to fetch")
            renderPosts(renderer, project, toRender, clean, newEntries, idOf)
            newEntries.forEach { titleById[it.id] = it.title }
            // Rebuild the index in full archive order (places backdated posts right).
            val finalEntries = scanned.mapNotNull { perma ->
                val id = idOf(perma)
                val f = project.postPdf(id)
                if (f.exists() && f.length() > 0) {
                    PostEntry(id, perma, titleById[id] ?: "Пост $id")
                } else null
            }
            val book = mergeProject(project, finalEntries, tree)
            ConvertBus.log("[deep] added ${newEntries.size}, total ${finalEntries.size}")
            if (book != null && autoRag) exportProjectRag(project, ragChunk, ragOverlap, tree)
            finish(book != null, book)
        } else if (existing.isNotEmpty()) {
            // ---- UPDATE (fast: only the new top of the feed) ----
            ConvertBus.log("[update] checking ${project.name} for new posts…")
            val fresh = profile?.scanNew(renderer, project.base, step, from, max, knownIds, note)
                ?: scanNewPosts(renderer, project.base, step, from, max, knownIds)
            if (ConvertBus.cancelRequested) { finish(false, null); return }
            if (fresh.isEmpty()) {
                ConvertBus.log("[update] already up to date")
                val existingBook = project.primaryBook()
                if (existingBook != null) { finish(true, existingBook); return }
                val book = mergeProject(project, existing, tree)
                finish(book != null, book); return
            }
            ConvertBus.log("[update] ${fresh.size} new post(s)")
            renderPosts(renderer, project, fresh, clean, newEntries, idOf)
            val merged = newEntries + existing
            val book = mergeProject(project, merged, tree)
            ConvertBus.log("[update] added ${newEntries.size}, total ${merged.size}")
            if (book != null && autoRag) exportProjectRag(project, ragChunk, ragOverlap, tree)
            finish(book != null, book)
        } else {
            // ---- FRESH ----
            val scanned = profile?.scanAll(renderer, project.base, step, from, max, note)
                ?: scanArchive(renderer, project.base, step, from, max)
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
            renderPosts(renderer, project, scanned.take(count), clean, newEntries, idOf)
            val book = mergeProject(project, newEntries, tree)
            if (book != null && autoRag) exportProjectRag(project, ragChunk, ragOverlap, tree)
            finish(book != null, book)
        }
    }

    /** Auto-export a RAG corpus for a project right after archiving. */
    private suspend fun exportProjectRag(
        project: Project, chunk: Int, overlap: Int, tree: String?
    ) {
        ConvertBus.log("[rag] auto-export for ${project.name}…")
        val out = File(getExternalFilesDir(null), "${project.name}_rag.jsonl")
        val n = withContext(Dispatchers.IO) {
            try {
                RagExporter.export(listOf(project), out, chunk, overlap) { d, t, s ->
                    ConvertBus.progress(d, t, s)
                    nm.notify(NID, progressNotif(s, d, t, t == 0))
                }
            } catch (t: Throwable) { ConvertBus.log("[rag] ${t.message}"); 0 }
        }
        if (n > 0 && tree != null) copyToTree(out, tree, "${project.name}_rag.jsonl", "application/json")
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

        // 2) years to probe: every year the calendar links to (these already
        //    include the back-dated years a user posted to, e.g. 1965), plus a
        //    blind walk down from the current year as a safety net.
        val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val calYears = sortedSetOf(Comparator.reverseOrder<Int>())
        cal.filter { yearRe.matches(it) }.forEach { val y = yr(it); if (y in 1900..curYear) calYears.add(y) }

        // probe one /YYYY/ page; return how many NEW months it contributed.
        suspend fun probeYear(y: Int): Int {
            val before = monthSet.size
            (withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                renderer.collectAllLinks("$base/$y/", SCAN_SETTLE_MS)
            } ?: emptyList()).filter { monthRe.matches(it) }.forEach { monthSet.add(it) }
            return monthSet.size - before
        }

        // 3a) always probe the explicit calendar years (incl. back-dated).
        for (y in calYears) {
            if (ConvertBus.cancelRequested) break
            nm.notify(NID, progressNotif(
                "Scanning archive: year $y… (${monthSet.size} months)", 0, 0, true))
            probeYear(y)
        }
        // 3b) blind walk current→1940, but bail out after a long run of empty
        //     years (a blog that started in 2005 has nothing in the 1940s–90s).
        var emptyRun = 0
        var seenNonEmpty = monthSet.isNotEmpty()
        for (y in curYear downTo 1940) {
            if (ConvertBus.cancelRequested) break
            if (y in calYears) continue                 // already probed above
            nm.notify(NID, progressNotif(
                "Scanning archive: year $y… (${monthSet.size} months)", 0, 0, true))
            val found = probeYear(y)
            if (found > 0) { seenNonEmpty = true; emptyRun = 0 }
            else if (seenNonEmpty && ++emptyRun >= 8) {
                ConvertBus.log("[scan] 8 empty years straight — stopping year probe at $y")
                break
            }
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
        clean: Boolean, out: ArrayList<PostEntry>, idOf: (String) -> String
    ) {
        val total = permalinks.size
        for ((i, perma) in permalinks.withIndex()) {
            if (ConvertBus.cancelRequested) { ConvertBus.log("[info] cancelled"); break }
            val id = idOf(perma)
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

    /**
     * Save the index and merge present per-post PDFs into book(s). Blogs larger
     * than [VOLUME_SIZE] posts are split into book_volNN.pdf volumes (each with
     * its own table of contents) so peak memory stays low. Returns the primary
     * book to open (volume 1, or the single book.pdf), or null on failure.
     */
    private suspend fun mergeProject(
        project: Project, entries: List<PostEntry>, tree: String?, volumeSize: Int = VOLUME_SIZE
    ): File? {
        val valid = entries.filter { project.postPdf(it.id).let { f -> f.exists() && f.length() > 0 } }
        if (valid.isEmpty()) return null
        project.saveEntries(valid)

        // Clear any stale outputs from a previous (differently-sized) run.
        project.bookFile.delete()
        project.volumeFiles().forEach { it.delete() }

        val chunks = valid.chunked(volumeSize)
        val single = chunks.size <= 1

        return withContext(Dispatchers.IO) {
            try {
                if (single) {
                    val files = valid.map { project.postPdf(it.id) }
                    val titles = valid.map { it.title }
                    ConvertBus.progress(valid.size, valid.size, "Merging ${valid.size} post(s)…")
                    nm.notify(NID, progressNotif("Merging ${valid.size} post(s)…", 0, 0, true))
                    val ok = BookBuilder.mergeWithToc(applicationContext, files, titles, project.bookFile)
                    if (ok && tree != null) copyToTree(project.bookFile, tree, "${project.name}.pdf")
                    if (ok) project.bookFile else null
                } else {
                    val total = chunks.size
                    chunks.forEachIndexed { i, chunk ->
                        val volNo = i + 1
                        val msg = "Merging volume $volNo/$total (${chunk.size} posts)…"
                        ConvertBus.log("[merge] $msg")
                        ConvertBus.progress(volNo, total, msg)
                        nm.notify(NID, progressNotif(msg, volNo, total, false))
                        val files = chunk.map { project.postPdf(it.id) }
                        val titles = chunk.map { it.title }
                        val vol = project.volumeFile(volNo)
                        val ok = BookBuilder.mergeWithToc(
                            applicationContext, files, titles, vol,
                            heading = "Содержание — том $volNo из $total"
                        )
                        if (ok && tree != null)
                            copyToTree(vol, tree, "${project.name} — том %02d.pdf".format(volNo))
                    }
                    project.primaryBook()
                }
            } catch (t: Throwable) {
                ConvertBus.log("[merge] error: ${t.message}"); null
            }
        }
    }

    // ===================== RAG (vector-DB corpus) =========================

    private suspend fun runRag(intent: Intent, name: String, tree: String?) {
        val dirs = intent.getStringArrayListExtra(EXTRA_RAG_DIRS) ?: arrayListOf()
        val projects = dirs.map { Project(File(it)) }.filter { it.entries().isNotEmpty() }
        if (projects.isEmpty()) {
            ConvertBus.log("[rag] no projects with saved posts"); finishRag(false, null); return
        }
        ConvertBus.log("[rag] building corpus from ${projects.size} project(s)…")
        val chunk = intent.getIntExtra(EXTRA_RAG_CHUNK, RAG_CHUNK)
        val overlap = intent.getIntExtra(EXTRA_RAG_OVERLAP, RAG_OVERLAP)
        val out = File(getExternalFilesDir(null), "$name.jsonl")
        val docs = withContext(Dispatchers.IO) {
            try {
                RagExporter.export(projects, out, chunk, overlap) { d, t, s ->
                    ConvertBus.progress(d, t, s)
                    nm.notify(NID, progressNotif(s, d, t, t == 0))
                }
            } catch (t: Throwable) { ConvertBus.log("[rag] error: ${t.message}"); -1 }
        }
        if (docs <= 0) { finishRag(false, null); return }
        if (tree != null) copyToTree(out, tree, "$name.jsonl", "application/json")
        finishRag(true, out)
    }

    private suspend fun runTgRag(intent: Intent, name: String, tree: String?) {
        val tg = intent.getStringExtra(EXTRA_TG_TREE) ?: ""
        if (tg.isEmpty()) { finishRag(false, null); return }
        ConvertBus.log("[tg] reading Telegram export…")
        val chunk = intent.getIntExtra(EXTRA_RAG_CHUNK, RAG_CHUNK)
        val overlap = intent.getIntExtra(EXTRA_RAG_OVERLAP, RAG_OVERLAP)
        val out = File(getExternalFilesDir(null), "$name.jsonl")
        val n = withContext(Dispatchers.IO) {
            try {
                TelegramImporter.export(applicationContext, tg, out, chunk, overlap) { d, t, s ->
                    ConvertBus.progress(d, t, s)
                    nm.notify(NID, progressNotif(s, d, t, t == 0))
                }
            } catch (t: Throwable) { ConvertBus.log("[tg] error: ${t.message}"); -1 }
        }
        if (n <= 0) { finishRag(false, null); return }
        if (tree != null) copyToTree(out, tree, "$name.jsonl", "application/json")
        finishRag(true, out)
    }

    private fun finishRag(ok: Boolean, file: File?) {
        running = false
        sampler?.cancel(); sampler = null
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(true)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "RAG dataset ready" else "RAG export failed")
            .setContentText(file?.let { "${it.name} (${it.length() / 1024} KB) — tap to share" } ?: "")
            .setAutoCancel(true)
        if (ok && file != null) n.setContentIntent(shareIntent(file, "application/json"))
        nm.notify(NID + 1, n.build())
        ConvertBus.finished(ok, null)
        stopSelf()
    }

    private fun shareIntent(file: File, mime: String): PendingIntent {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "Share dataset")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 1, chooser, flags)
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
            try { BookBuilder.mergeWithToc(applicationContext, pageFiles, outTitles, book) }
            catch (t: Throwable) { ConvertBus.log("[merge] error: ${t.message}"); false }
        }
        workDir.listFiles()?.forEach { it.delete() }
        if (ok && tree != null) copyToTree(book, tree, "$name.pdf")
        finish(ok, if (ok) book else null)
    }

    // ===================== shared ========================================

    private fun finish(ok: Boolean, book: File?) {
        running = false
        sampler?.cancel(); sampler = null
        renderer?.destroy(); renderer = null
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(true)
        nm.notify(NID + 1, if (ok && book != null) doneNotif(book) else failedNotif())
        ConvertBus.finished(ok, book)
        stopSelf()
    }

    private fun copyToTree(
        src: File, treeUri: String, displayName: String, mime: String = "application/pdf"
    ) {
        try {
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeUri)) ?: return
            tree.findFile(displayName)?.delete()
            val doc = tree.createFile(mime, displayName) ?: return
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

    /** Keep Wi-Fi at full power during downloads (radio used to the full). */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "lj2pdf:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        wifiLock = null
    }

    /** Periodically publish network/CPU stats (speed, ping, threads, ETA). */
    private fun startSampler(host: String?) {
        sampler?.cancel()
        sampler = scope.launch {
            var prevBytes = 0L
            var prevDone = 0
            var prevT = System.nanoTime()
            var ping = -1
            var tick = 0
            while (isActive) {
                delay(1000)
                tick++
                val now = System.nanoTime()
                val dt = (now - prevT) / 1e9
                val bytes = ConvertBus.bytesTotal.get()
                val bps = if (dt > 0) ((bytes - prevBytes) / dt).toLong().coerceAtLeast(0) else 0
                val done = ConvertBus.done
                val total = ConvertBus.total
                val postsPerSec = if (dt > 0) (done - prevDone) / dt else 0.0
                val eta = if (postsPerSec > 0.01 && total > done)
                    ((total - done) / postsPerSec).toInt() else -1
                prevBytes = bytes; prevDone = done; prevT = now
                if (tick % 3 == 0 && host != null) ping = pingHost(host)
                ConvertBus.stats(bytes, bps, ping, ConvertBus.activeRequests.get(), eta)
            }
        }
    }

    private suspend fun pingHost(host: String): Int = withContext(Dispatchers.IO) {
        try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, 443), 2000) }
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (_: Throwable) { -1 }
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
