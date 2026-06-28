package com.drmd.lj2pdf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
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

        rgMode.setOnCheckedChangeListener { _, _ -> applyMode() }
        applyMode()

        findViewById<MaterialButton>(R.id.btnChooseOut).setOnClickListener {
            try { pickFolder.launch(null) } catch (t: Throwable) { toast("No file picker available.") }
        }
        btnStart.setOnClickListener { onStart() }
        btnCancel.setOnClickListener {
            ConvertBus.cancelRequested = true
            btnCancel.isEnabled = false
            txtStatus.text = "Cancelling…"
        }
        btnOpen.setOnClickListener { openBook() }
    }

    private enum class Mode { LJ, TEMPLATE, SINGLE }

    private fun mode(): Mode = when (rgMode.checkedRadioButtonId) {
        R.id.rbTemplate -> Mode.TEMPLATE
        R.id.rbSingle -> Mode.SINGLE
        else -> Mode.LJ
    }

    private fun applyMode() {
        when (mode()) {
            Mode.LJ -> {
                rowPager.visibility = View.VISIBLE
                tilStep.visibility = View.VISIBLE
                tilUrl.hint = "Blog URL"
                edtUrl.setText(prefillIfPlaceholder("https://"))
                txtHint.text = "Page k = BASE/?skip=(k-1)×entries. Keep 20 for LiveJournal."
            }
            Mode.TEMPLATE -> {
                rowPager.visibility = View.VISIBLE
                tilStep.visibility = View.GONE
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

    private fun prefillIfPlaceholder(p: String): String {
        val cur = edtUrl.text?.toString().orEmpty()
        return if (cur.isBlank()) p else cur
    }

    private fun onStart() {
        if (ConvertBus.running) { toast("Already running."); return }
        val raw = edtUrl.text?.toString()?.trim().orEmpty()
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            toast("Enter a URL starting with http(s)://"); return
        }

        val urls = ArrayList<String>()
        val titles = ArrayList<String>()
        when (mode()) {
            Mode.LJ -> {
                val base = raw.trimEnd('/')
                val from = edtFrom.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                var to = edtTo.text?.toString()?.toIntOrNull() ?: from
                if (to < from) to = from
                val step = edtStep.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 20
                for (k in from..to) {
                    val skip = (k - 1) * step
                    urls.add(if (skip == 0) "$base/" else "$base/?skip=$skip")
                    titles.add("Страница $k")
                }
            }
            Mode.TEMPLATE -> {
                if (!raw.contains("{n}")) { toast("Template must contain {n}"); return }
                val from = edtFrom.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                var to = edtTo.text?.toString()?.toIntOrNull() ?: from
                if (to < from) to = from
                for (k in from..to) {
                    urls.add(raw.replace("{n}", k.toString()))
                    titles.add("Страница $k")
                }
            }
            Mode.SINGLE -> {
                urls.add(raw)
                titles.add(Uri.parse(raw).host ?: "Page")
            }
        }
        if (urls.isEmpty()) { toast("Nothing to convert."); return }

        // Ask for the notification permission (Android 13+) so the progress shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val name = (Uri.parse(raw.replace("{n}", "1")).host
            ?.replace(Regex("[^A-Za-z0-9.-]"), "_") ?: "book") + "_book"

        txtLog.text = ""
        txtStatus.text = "Starting…"
        setBusy(true)

        val intent = Intent(this, ConvertService::class.java).apply {
            putStringArrayListExtra(ConvertService.EXTRA_URLS, urls)
            putStringArrayListExtra(ConvertService.EXTRA_TITLES, titles)
            putExtra(ConvertService.EXTRA_NAME, name)
            treeUri?.let { putExtra(ConvertService.EXTRA_TREE, it) }
        }
        ContextCompat.startForegroundService(this, intent)
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

    private fun openBook() {
        val book = ConvertBus.lastBook ?: return
        if (!book.exists()) { toast("Book not found."); return }
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
    }

    override fun onPause() {
        ConvertBus.observer = null
        super.onPause()
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
