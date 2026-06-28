package com.drmd.lj2pdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var edtUrl: EditText
    private lateinit var edtFrom: EditText
    private lateinit var edtTo: EditText
    private lateinit var edtStep: EditText
    private lateinit var btnStart: Button
    private lateinit var btnCancel: Button
    private lateinit var btnOpen: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var formScroll: ScrollView

    private var job: Job? = null
    @Volatile private var cancelRequested = false
    private var lastBook: File? = null

    // Per-page settle delay and hard timeout.
    private val settleMs = 1500L
    private val pageTimeoutMs = 45_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PDFBox-Android needs its asset loader initialised once.
        PDFBoxResourceLoader.init(applicationContext)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        edtUrl = findViewById(R.id.edtUrl)
        edtFrom = findViewById(R.id.edtFrom)
        edtTo = findViewById(R.id.edtTo)
        edtStep = findViewById(R.id.edtStep)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        btnOpen = findViewById(R.id.btnOpen)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        formScroll = findViewById(R.id.formScroll)

        btnStart.setOnClickListener { start() }
        btnCancel.setOnClickListener {
            cancelRequested = true
            btnCancel.isEnabled = false
            setStatus("Cancelling…")
            log("[ui] cancel requested")
        }
        btnOpen.setOnClickListener { openBook() }
    }

    private fun start() {
        val base = edtUrl.text.toString().trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            toast("Enter the blog URL, e.g. https://someblog.livejournal.com")
            return
        }
        val from = edtFrom.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        var to = edtTo.text.toString().toIntOrNull() ?: from
        if (to < from) to = from
        val step = edtStep.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 20

        val host = Uri.parse(base).host?.replace(Regex("[^A-Za-z0-9.-]"), "_") ?: "blog"
        val outDir = File(getExternalFilesDir(null), "${host}_book").apply { mkdirs() }

        cancelRequested = false
        lastBook = null
        txtLog.text = ""
        setBusy(true)
        log("[info] output folder: ${outDir.absolutePath}")

        val renderer = WebViewPdfRenderer(web) { line -> runOnUiThread { log(line) } }

        job = lifecycleScope.launch {
            val pageFiles = ArrayList<File>()
            val titles = ArrayList<String>()

            for (k in from..to) {
                if (cancelRequested) { log("[info] cancelled"); break }
                val skip = (k - 1) * step
                val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
                setStatus("Page $k of $to…")
                log("[page $k] $url")

                val outFile = File(outDir, "page_%04d.pdf".format(k))
                val ok = withTimeoutOrNull(pageTimeoutMs) {
                    renderer.renderUrlToPdf(url, outFile, settleMs)
                } ?: false

                if (ok && outFile.length() > 0) {
                    pageFiles.add(outFile)
                    titles.add("Страница $k")
                    log("  -> ok (${outFile.length() / 1024} KB)")
                } else {
                    log("  -> FAILED (skipped)")
                }
            }

            if (pageFiles.isEmpty()) {
                setStatus("Nothing converted.")
                log("[done] no pages were converted")
                setBusy(false)
                return@launch
            }

            setStatus("Merging ${pageFiles.size} page(s) into book…")
            log("[merge] building book.pdf with table of contents…")
            val book = File(outDir, "book.pdf")
            val merged = withContext(Dispatchers.IO) {
                try {
                    BookBuilder.mergeWithToc(pageFiles, titles, book)
                } catch (t: Throwable) {
                    runOnUiThread { log("[merge] error: ${t.message}") }
                    false
                }
            }

            if (merged) {
                lastBook = book
                btnOpen.isEnabled = true
                setStatus("Done — book.pdf ready (${book.length() / 1024} KB).")
                log("[done] ${book.absolutePath}")
            } else {
                setStatus("Merge failed.")
                log("[done] merge failed; per-page PDFs remain in the folder")
            }
            setBusy(false)
        }
    }

    private fun openBook() {
        val book = lastBook ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", book)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(view, "Open book.pdf"))
        } catch (t: Throwable) {
            toast("No PDF viewer installed.")
        }
    }

    private fun setBusy(busy: Boolean) {
        btnStart.isEnabled = !busy
        btnCancel.isEnabled = busy
        edtUrl.isEnabled = !busy
        edtFrom.isEnabled = !busy
        edtTo.isEnabled = !busy
        edtStep.isEnabled = !busy
    }

    private fun setStatus(s: String) {
        txtStatus.text = s
    }

    private fun log(line: String) {
        txtLog.append(line + "\n")
        formScroll.post { formScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        cancelRequested = true
        job?.cancel()
        super.onDestroy()
    }
}
