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
                tilUrl.hint = "Blog URL"
                txtHint.text = "Scan the blog, then choose how many to save — or " +
                    "tick «automatically» to grab it all. From = start page."
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

    /** If the app crashed last time, show the captured stack trace to share. */
    private fun showLastCrashIfAny() {
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
        val projects = Projects.list(this)
        if (projects.isEmpty()) {
            toast("No projects yet — archive a blog first (LiveJournal + Full posts).")
            return
        }
        val names = projects.map { "${it.name}  (${it.entries().size} posts)" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(names) { _, i -> showProjectActions(projects[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showProjectActions(p: Project) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Update (add new posts)", "Open PDF", "Delete")) { _, i ->
                when (i) {
                    0 -> updateProject(p)
                    1 -> openFile(p.bookFile)
                    2 -> confirmDelete(p)
                }
            }
            .show()
    }

    private fun confirmDelete(p: Project) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete ${p.name}?")
            .setMessage("Removes the saved posts and book for this blog.")
            .setPositiveButton("Delete") { _, _ -> Projects.delete(p); toast("Deleted ${p.name}") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateProject(p: Project) {
        if (ConvertBus.running) { toast("Already running."); return }
        if (p.base.isBlank()) { toast("Project has no saved URL."); return }
        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_MODE, "lj_project")
            putExtra(ConvertService.EXTRA_AUTO, true)        // update = add all new posts
            putExtra(ConvertService.EXTRA_BASE, p.base)
            putExtra(ConvertService.EXTRA_FROM, 1)
            putExtra(ConvertService.EXTRA_MAX, 2000)
            putExtra(ConvertService.EXTRA_CLEAN, cbClean.isChecked)
            treeUri?.let { putExtra(ConvertService.EXTRA_TREE, it) }
        }
        launchService(intent, "Updating ${p.name}…")
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
    }

    override fun onScanReady(total: Int) {
        progress.isIndeterminate = true
        txtStatus.text = "Scanned $total — choose how many"
        showSelectionDialog(total)
    }

    override fun onProgress(done: Int, total: Int, status: String) {
        txtStatus.text = status
        progress.isIndeterminate = false
        progress.max = maxOf(total, 1)
        progress.progress = done
    }

    override fun onLog(line: String) {
        txtLog.append(line + "\n")
        scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDone(ok: Boolean, book: File?) {
        setBusy(false)
        txtStatus.text = if (ok && book != null)
            "Done — ${book.name} (${book.length() / 1024} KB)" else "Failed."
        btnOpen.isEnabled = ok && book != null
    }
}
