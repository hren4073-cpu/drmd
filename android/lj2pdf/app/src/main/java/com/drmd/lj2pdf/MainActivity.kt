package com.drmd.lj2pdf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class MainActivity : AppCompatActivity(), ConvertBus.Observer {

    private lateinit var rgMode: RadioGroup
    private lateinit var tilUrl: TextInputLayout
    private lateinit var edtUrl: TextInputEditText
    private lateinit var rowPager: View
    private lateinit var tilStep: TextInputLayout
    private lateinit var cbAll: CheckBox
    private lateinit var cbArchive: CheckBox
    private lateinit var cbClean: CheckBox
    private lateinit var tilTo: TextInputLayout
    private lateinit var edtFrom: TextInputEditText
    private lateinit var edtTo: TextInputEditText
    private lateinit var edtStep: TextInputEditText
    private lateinit var txtHint: TextView
    private lateinit var txtOut: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var circular: CircularProgressIndicator
    private lateinit var progress2: LinearProgressIndicator
    private lateinit var txtPercent: TextView
    private lateinit var txtCount: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnOpen: MaterialButton
    private lateinit var txtLog: TextView
    private lateinit var scrollRoot: NestedScrollView

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
    private var treeUri: String? = null

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Throwable) { /* best effort */ }
                treeUri = uri.toString()
                prefs.edit().putString("tree", treeUri).apply()
                updateOutLabel()
            }
        }

    private val askNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* proceed anyway */ }

    private val pickTgFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}
                startTgRag(uri.toString())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rgMode = findViewById(R.id.rgMode)
        tilUrl = findViewById(R.id.tilUrl)
        edtUrl = findViewById(R.id.edtUrl)
        rowPager = findViewById(R.id.rowPager)
        tilStep = findViewById(R.id.tilStep)
        cbAll = findViewById(R.id.cbAll)
        cbArchive = findViewById(R.id.cbArchive)
        cbClean = findViewById(R.id.cbClean)
        tilTo = findViewById(R.id.tilTo)
        edtFrom = findViewById(R.id.edtFrom)
        edtTo = findViewById(R.id.edtTo)
        edtStep = findViewById(R.id.edtStep)
        txtHint = findViewById(R.id.txtHint)
        txtOut = findViewById(R.id.txtOut)
        progress = findViewById(R.id.progress)
        circular = findViewById(R.id.circular)
        progress2 = findViewById(R.id.progress2)
        txtPercent = findViewById(R.id.txtPercent)
        txtCount = findViewById(R.id.txtCount)
        txtStatus = findViewById(R.id.txtStatus)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        btnOpen = findViewById(R.id.btnOpen)
        txtLog = findViewById(R.id.txtLog)
        scrollRoot = findViewById(R.id.scrollRoot)

        treeUri = prefs.getString("tree", null)
        updateOutLabel()
        showLastCrashIfAny()

        rgMode.setOnCheckedChangeListener { _, _ -> applyMode() }
        applyMode()

        findViewById<MaterialButton>(R.id.btnChooseOut).setOnClickListener {
            try { pickFolder.launch(null) } catch (t: Throwable) { toast("No file picker available.") }
        }
        btnStart.setOnClickListener { startConversion() }
        btnCancel.setOnClickListener {
            ConvertBus.cancelRequested = true
            btnCancel.isEnabled = false
            txtStatus.text = "Cancelling…"
            try {
                startService(Intent(this, ConvertService::class.java)
                    .setAction(ConvertService.ACTION_STOP))
            } catch (_: Throwable) { /* service may have stopped */ }
        }
        btnOpen.setOnClickListener { openBook() }
        findViewById<MaterialButton>(R.id.btnProjects).setOnClickListener { showProjectsDialog() }
        findViewById<MaterialButton>(R.id.btnLog).setOnClickListener { showLogDialog() }
        findViewById<MaterialButton>(R.id.btnTg).setOnClickListener {
            toast("Pick a Telegram export folder (with messages*.html or result.json)")
            try { pickTgFolder.launch(null) } catch (t: Throwable) { toast("No folder picker.") }
        }
        findViewById<MaterialButton>(R.id.btnMore).setOnClickListener { showMore(it) }
    }

    private enum class Mode { LJ, TEMPLATE, SINGLE }

    private fun mode(): Mode = when (rgMode.checkedRadioButtonId) {
        R.id.rbTemplate -> Mode.TEMPLATE
        R.id.rbSingle -> Mode.SINGLE
        else -> Mode.LJ
    }

    private fun applyMode() {
        val m = mode()
        val lj = m == Mode.LJ
        cbAll.visibility = if (lj) View.VISIBLE else View.GONE
        cbArchive.visibility = if (lj) View.VISIBLE else View.GONE
        when (m) {
            Mode.LJ -> {
                rowPager.visibility = View.VISIBLE
                tilStep.visibility = View.VISIBLE
                tilTo.visibility = View.GONE        // LJ uses scan/auto, not To
                tilUrl.hint = "Blog / site URL"
                txtHint.text = "Platform auto-detected by URL (LiveJournal, Habr, generic). " +
                    "Keep «Full posts» on. Scan → choose how many, or «automatically» for all."
            }
            Mode.TEMPLATE -> {
                rowPager.visibility = View.VISIBLE
                tilStep.visibility = View.GONE
                tilTo.visibility = View.VISIBLE
                tilUrl.hint = "URL template with {n}"
                txtHint.text = "Use {n} for the page number, e.g. https://site/blog/page/{n}"
            }
            Mode.SINGLE -> {
                rowPager.visibility = View.GONE
                tilStep.visibility = View.GONE
                tilUrl.hint = "Page URL"
                txtHint.text = "A single page → one PDF."
            }
        }
    }

    private fun startConversion() {
        if (ConvertBus.running) { toast("Already running."); return }
        val raw = edtUrl.text?.toString()?.trim().orEmpty()
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            toast("Enter a URL starting with http(s)://"); return
        }
        val from = edtFrom.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val step = edtStep.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 20
        val clean = cbClean.isChecked
        val name = (Uri.parse(raw.replace("{n}", "1")).host
            ?.replace(Regex("[^A-Za-z0-9.-]"), "_") ?: "book") + "_book"

        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_NAME, name)
            putExtra(ConvertService.EXTRA_CLEAN, clean)
            treeUri?.let { putExtra(ConvertService.EXTRA_TREE, it) }
        }

        when (mode()) {
            Mode.LJ -> {
                intent.putExtra(
                    ConvertService.EXTRA_MODE,
                    if (cbArchive.isChecked) "lj_project" else "lj_pages"
                )
                intent.putExtra(ConvertService.EXTRA_AUTO, cbAll.isChecked)
                intent.putExtra(ConvertService.EXTRA_BASE, raw.trimEnd('/'))
                intent.putExtra(ConvertService.EXTRA_STEP, step)
                intent.putExtra(ConvertService.EXTRA_FROM, from)
                intent.putExtra(ConvertService.EXTRA_MAX, 2000)
            }
            Mode.TEMPLATE -> {
                if (!raw.contains("{n}")) { toast("Template must contain {n}"); return }
                var to = edtTo.text?.toString()?.toIntOrNull() ?: from
                if (to < from) to = from
                val urls = ArrayList<String>()
                val titles = ArrayList<String>()
                for (k in from..to) { urls.add(raw.replace("{n}", k.toString())); titles.add("Страница $k") }
                intent.putExtra(ConvertService.EXTRA_MODE, "list")
                intent.putStringArrayListExtra(ConvertService.EXTRA_URLS, urls)
                intent.putStringArrayListExtra(ConvertService.EXTRA_TITLES, titles)
            }
            Mode.SINGLE -> {
                intent.putExtra(ConvertService.EXTRA_MODE, "list")
                intent.putStringArrayListExtra(ConvertService.EXTRA_URLS, arrayListOf(raw))
                intent.putStringArrayListExtra(
                    ConvertService.EXTRA_TITLES, arrayListOf(Uri.parse(raw).host ?: "Page")
                )
            }
        }

        intent.withRag(true)
        launchService(intent, "Starting…")
    }

    /**
     * Start the service, after making sure background work will actually keep
     * running: requests the notification permission and the "draw over other
     * apps" permission (the latter lets the offscreen WebView render in the
     * background instead of stalling/resetting when minimised).
     */
    private fun launchService(intent: Intent, status: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestBatteryExemptionIfNeeded()
        val go = {
            txtLog.text = ""
            txtStatus.text = status
            setBusy(true)
            ContextCompat.startForegroundService(this, intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Фоновая работа")
                .setMessage(
                    "Чтобы скачивание не сбрасывалось при сворачивании, разрешите " +
                    "«Поверх других приложений» — это нужно для рендера страниц в фоне."
                )
                .setPositiveButton("Разрешить") { _, _ -> openOverlaySettings() }
                .setNegativeButton("Запустить так") { _, _ -> go() }
                .setCancelable(false)
                .show()
        } else go()
    }

    /** One-tap system prompt to stop the OS from killing the background job. */
    private fun requestBatteryExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) { /* OEM may not support it */ }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            toast("Включите разрешение и снова нажмите Start")
        } catch (_: Throwable) {
            toast("Откройте: Настройки → Поверх других приложений")
        }
    }

    /** Tell the running service how many scanned items to download. */
    private fun sendSelect(count: Int) {
        try {
            startService(
                Intent(this, ConvertService::class.java)
                    .setAction(ConvertService.ACTION_SELECT)
                    .putExtra(ConvertService.EXTRA_SELECT_COUNT, count)
            )
            txtStatus.text = "Downloading $count…"
        } catch (_: Throwable) { /* ignore */ }
    }

    /** After a scan, let the user pick how much of the blog to save. */
    private fun showSelectionDialog(total: Int) {
        if (!alive()) return
        if (total <= 0) return
        val half = (total + 1) / 2
        val third = (total + 2) / 3
        val options = arrayOf(
            "All ($total)",
            "Half (~$half)",
            "A third (~$third)",
            "Custom…"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Found $total — how many (freshest first)?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendSelect(total)
                    1 -> sendSelect(half)
                    2 -> sendSelect(third)
                    3 -> askCustomCount(total)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                ConvertBus.cancelRequested = true
                sendStop()
            }
            .setCancelable(false)
            .show()
    }

    private fun askCustomCount(total: Int) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(total.toString())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("How many (1..$total)?")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().toIntOrNull()?.coerceIn(1, total) ?: total
                sendSelect(n)
            }
            .setNegativeButton("Cancel") { _, _ -> ConvertBus.cancelRequested = true; sendStop() }
            .show()
    }

    private fun sendStop() {
        try {
            startService(Intent(this, ConvertService::class.java)
                .setAction(ConvertService.ACTION_STOP))
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun updateOutLabel() {
        val t = treeUri
        txtOut.text = if (t == null) {
            "Output: app folder (use Open PDF / Share)"
        } else {
            val name = try {
                DocumentFile.fromTreeUri(this, Uri.parse(t))?.name
            } catch (_: Throwable) { null }
            "Output folder: ${name ?: t}"
        }
    }

    /** View / share / clear the persistent bug log. */
    private fun showLogDialog() {
        if (!alive()) return
        val text = Logx.read().ifBlank { "(log is empty)" }
        // show the tail (most recent) so big logs stay readable
        val tail = if (text.length > 8000) "…\n" + text.takeLast(8000) else text
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bug log")
            .setMessage(tail)
            .setPositiveButton("Share") { _, _ -> shareText(Logx.read()) }
            .setNeutralButton("Clear") { _, _ -> Logx.clear(); toast("Log cleared") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun shareText(text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "lj2pdf log")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(i, "Share log"))
        } catch (_: Throwable) {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", text))
                toast("Copied to clipboard")
            } catch (_: Throwable) {}
        }
    }

    /** If the app crashed last time, show the captured stack trace to share. */
    private fun showLastCrashIfAny() {
        if (!alive()) return
        val f = File(filesDir, "crash.txt")
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Throwable) { "" }
        f.delete()
        if (text.isBlank()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Last crash report")
            .setMessage(text.take(4000))
            .setPositiveButton("Copy") { _, _ ->
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                    toast("Copied — paste it to the developer")
                } catch (_: Throwable) {}
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun openBook() = openFile(ConvertBus.lastBook)

    private fun openFile(book: File?) {
        if (book == null || !book.exists()) { toast("Book not found."); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", book)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(view, "Open book.pdf"))
        } catch (t: Throwable) {
            toast("No PDF viewer installed.")
        }
    }

    // -- projects (saved blogs, incremental update) -----------------------

    private fun showProjectsDialog() {
        if (!alive()) return
        val projects = Projects.list(this)
        if (projects.isEmpty()) {
            toast("No projects yet — archive a blog first (LiveJournal + Full posts).")
            return
        }
        val names = projects.map { "${it.name}  (${it.entries().size} posts)" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(names) { _, i -> showProjectActions(projects[i]) }
            .setNeutralButton("Merge → RAG") { _, _ -> startRag(projects, "merged_rag") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showProjectActions(p: Project) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(
                arrayOf(
                    "Update (new posts)",
                    "Deep rescan (incl. backdated)",
                    "Export RAG (JSONL)",
                    "Open PDF",
                    "Delete"
                )
            ) { _, i ->
                when (i) {
                    0 -> updateProject(p, deep = false)
                    1 -> updateProject(p, deep = true)
                    2 -> startRag(listOf(p), "${p.name}_rag")
                    3 -> openFile(p.bookFile)
                    4 -> confirmDelete(p)
                }
            }
            .show()
    }

    // -- settings (the "⋮" menu) ------------------------------------------

    private fun ragChunk() = prefs.getInt("rag_chunk", 1000).coerceIn(200, 8000)
    private fun ragOverlap() = prefs.getInt("rag_overlap", 150).coerceIn(0, 2000)
    private fun autoRag() = prefs.getBoolean("auto_rag", false)

    /** Add RAG extras to a service intent so the job uses the user's settings. */
    private fun Intent.withRag(includeAuto: Boolean): Intent {
        putExtra(ConvertService.EXTRA_RAG_CHUNK, ragChunk())
        putExtra(ConvertService.EXTRA_RAG_OVERLAP, ragOverlap())
        if (includeAuto) putExtra(ConvertService.EXTRA_AUTO_RAG, autoRag())
        return this
    }

    private fun showMore(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "RAG settings (chunk / overlap)")
        pm.menu.add(0, 2, 0, "Auto-RAG after archive").apply {
            isCheckable = true; isChecked = autoRag()
        }
        pm.menu.add(0, 3, 0, "About / roadmap")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { ragSettingsDialog(); true }
                2 -> {
                    prefs.edit().putBoolean("auto_rag", !autoRag()).apply()
                    toast("Auto-RAG after archive: ${if (autoRag()) "on" else "off"}")
                    true
                }
                3 -> { showAbout(); true }
                else -> false
            }
        }
        pm.show()
    }

    private fun ragSettingsDialog() {
        if (!alive()) return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(label: String, value: Int): android.widget.EditText {
            box.addView(android.widget.TextView(this).apply { text = label })
            return android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }.also { box.addView(it) }
        }
        val chunk = field("Chunk size (characters)", ragChunk())
        val ov = field("Overlap (characters)", ragOverlap())
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("RAG settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putInt("rag_chunk", chunk.text.toString().toIntOrNull() ?: 1000)
                    .putInt("rag_overlap", ov.text.toString().toIntOrNull() ?: 150)
                    .apply()
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        if (!alive()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(
                "Blog/site → PDF + RAG archiver.\n\n" +
                "Platforms auto-detected: LiveJournal, Habr, generic sites (TOC/" +
                "forums with pagination). RAG export builds a JSONL corpus for " +
                "local LLMs; «Auto-RAG» also makes it right after archiving.\n\n" +
                "Roadmap: DTF/TJournal (osnova API), smarter forum structure, " +
                "Sefaria + translator hook."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Build a RAG corpus from a Telegram Desktop export folder. */
    private fun startTgRag(tgUri: String) {
        if (ConvertBus.running) { toast("Already running."); return }
        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_MODE, "tg_rag")
            putExtra(ConvertService.EXTRA_NAME, "telegram_rag")
            putExtra(ConvertService.EXTRA_TG_TREE, tgUri)
            this@MainActivity.treeUri?.let { putExtra(ConvertService.EXTRA_TREE, it) }
        }
        intent.withRag(false)
        launchService(intent, "Telegram → RAG…")
    }

    /** Build a RAG (vector-DB) corpus JSONL from one or more projects. */
    private fun startRag(projects: List<Project>, name: String) {
        if (ConvertBus.running) { toast("Already running."); return }
        val withPosts = projects.filter { it.entries().isNotEmpty() }
        if (withPosts.isEmpty()) { toast("No saved posts to export."); return }
        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_MODE, "rag")
            putExtra(ConvertService.EXTRA_NAME, name)
            putStringArrayListExtra(
                ConvertService.EXTRA_RAG_DIRS,
                ArrayList(withPosts.map { it.dir.absolutePath })
            )
            treeUri?.let { putExtra(ConvertService.EXTRA_TREE, it) }
        }
        intent.withRag(false)
        launchService(intent, "RAG: $name…")
    }

    private fun confirmDelete(p: Project) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete ${p.name}?")
            .setMessage("Removes the saved posts and book for this blog.")
            .setPositiveButton("Delete") { _, _ -> Projects.delete(p); toast("Deleted ${p.name}") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateProject(p: Project, deep: Boolean) {
        if (ConvertBus.running) { toast("Already running."); return }
        if (p.base.isBlank()) { toast("Project has no saved URL."); return }
        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_MODE, "lj_project")
            putExtra(ConvertService.EXTRA_AUTO, true)        // add all new posts
            putExtra(ConvertService.EXTRA_DEEP, deep)        // full archive walk
            putExtra(ConvertService.EXTRA_BASE, p.base)
            putExtra(ConvertService.EXTRA_FROM, 1)
            putExtra(ConvertService.EXTRA_MAX, 2000)
            putExtra(ConvertService.EXTRA_CLEAN, cbClean.isChecked)
            treeUri?.let { putExtra(ConvertService.EXTRA_TREE, it) }
        }
        intent.withRag(true)
        launchService(intent, if (deep) "Deep rescan: ${p.name}…" else "Updating ${p.name}…")
    }

    private fun setBusy(busy: Boolean) {
        btnStart.isEnabled = !busy
        btnCancel.isEnabled = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        rgMode.isEnabled = !busy
        edtUrl.isEnabled = !busy
        edtFrom.isEnabled = !busy
        edtTo.isEnabled = !busy
        edtStep.isEnabled = !busy
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Safe to show a dialog only while the activity window is valid. */
    private fun alive(): Boolean = !isFinishing && !isDestroyed

    // -- observe the service ----------------------------------------------

    override fun onResume() {
        super.onResume()
        ConvertBus.observer = this
        // Re-sync UI with whatever the service is doing.
        setBusy(ConvertBus.running)
        txtStatus.text = ConvertBus.lastStatus
        txtLog.text = ConvertBus.logText.toString()
        if (ConvertBus.total > 0) {
            progress.isIndeterminate = false
            progress.max = ConvertBus.total
            progress.progress = ConvertBus.done
        }
        setGauge(ConvertBus.done, ConvertBus.total)
        btnOpen.isEnabled = ConvertBus.lastBook?.exists() == true
        // If the service is parked waiting for a choice, re-show the picker.
        if (ConvertBus.awaitingSelection) showSelectionDialog(ConvertBus.scanTotal)
    }

    override fun onPause() {
        ConvertBus.observer = null
        super.onPause()
    }

    override fun onScanProgress(pagesScanned: Int, itemsFound: Int) {
        progress.isIndeterminate = true
        txtStatus.text = "Scanning… page $pagesScanned, $itemsFound found"
        setGaugeScanning(itemsFound)
    }

    override fun onScanReady(total: Int) {
        progress.isIndeterminate = true
        txtStatus.text = "Scanned $total — choose how many"
        setGauge(0, total)
        showSelectionDialog(total)
    }

    override fun onProgress(done: Int, total: Int, status: String) {
        txtStatus.text = status
        progress.isIndeterminate = false
        progress.max = maxOf(total, 1)
        progress.progress = done
        setGauge(done, total)
    }

    /** Determinate readiness gauge (circle + bar + count). */
    private fun setGauge(done: Int, total: Int) {
        if (total > 0) {
            val pct = (done * 100 / total).coerceIn(0, 100)
            circular.isIndeterminate = false
            circular.max = total; circular.setProgressCompat(done, true)
            progress2.isIndeterminate = false
            progress2.max = total; progress2.setProgressCompat(done, true)
            txtPercent.text = "$pct%"
            txtCount.text = "$done / $total"
        } else {
            circular.isIndeterminate = false
            circular.setProgressCompat(0, false)
            progress2.isIndeterminate = false
            progress2.setProgressCompat(0, false)
            txtPercent.text = "—"
            txtCount.text = "—"
        }
    }

    /** Indeterminate gauge while the structure scan runs (count unknown). */
    private fun setGaugeScanning(found: Int) {
        circular.isIndeterminate = true
        progress2.isIndeterminate = true
        txtPercent.text = "…"
        txtCount.text = "$found found"
    }

    override fun onLog(line: String) {
        txtLog.append(line + "\n")
        // Keep the on-screen board bounded so it can't bloat memory on long runs.
        val len = txtLog.length()
        if (len > 16_000) {
            val t = txtLog.text
            txtLog.text = t.subSequence(len - 12_000, len)
        }
        scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDone(ok: Boolean, book: File?) {
        setBusy(false)
        txtStatus.text = if (ok && book != null)
            "Done — ${book.name} (${book.length() / 1024} KB)" else "Failed."
        btnOpen.isEnabled = ok && book != null
        if (ok) { val t = maxOf(ConvertBus.total, 1); setGauge(t, t) }   // 100 %
    }
}
