package com.drmd.lj2pdf

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Renders web pages to PDF using a single offscreen [WebView] and Android's
 * native print pipeline ([PrintDocumentAdapter]) — no Chrome, no Ghostscript.
 *
 * All methods must be called from the main thread (WebView requirement).
 * The WebView is supplied by the host (kept in the view hierarchy, behind an
 * opaque overlay) so that it is laid out at full size and prints correctly.
 */
class WebViewPdfRenderer(
    private val web: WebView,
    private val log: (String) -> Unit
) {
    init {
        web.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
    }

    /** Load [url], wait for it to settle, then write a PDF to [outFile]. */
    suspend fun renderUrlToPdf(url: String, outFile: File, settleMs: Long): Boolean {
        val loaded = loadPage(url)
        if (!loaded) return false
        // Give late resources / web fonts / lazy images a moment to paint.
        delay(settleMs)
        return printToPdf(outFile)
    }

    private suspend fun loadPage(url: String): Boolean =
        suspendCancellableCoroutine { cont ->
            web.webViewClient = object : WebViewClient() {
                private var settled = false
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (!settled) {
                        settled = true
                        if (cont.isActive) cont.resume(true)
                    }
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    // Only fail on the main document; ignore broken sub-resources.
                    if (request.isForMainFrame && !settled) {
                        settled = true
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            web.loadUrl(url)
        }

    private suspend fun printToPdf(outFile: File): Boolean {
        val adapter = web.createPrintDocumentAdapter("page")
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        return driveAdapter(adapter, attrs, outFile)
    }

    /** Drive a [PrintDocumentAdapter] straight to a file (no print dialog). */
    private suspend fun driveAdapter(
        adapter: PrintDocumentAdapter,
        attrs: PrintAttributes,
        outFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        fun finish(ok: Boolean) {
            if (!resumed) {
                resumed = true
                if (cont.isActive) cont.resume(ok)
            }
        }

        try {
            adapter.onStart()
            adapter.onLayout(
                null, attrs, null,
                object : PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(
                        info: android.print.PrintDocumentInfo?,
                        changed: Boolean
                    ) {
                        var pfd: ParcelFileDescriptor? = null
                        try {
                            pfd = ParcelFileDescriptor.open(
                                outFile,
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_TRUNCATE
                            )
                            val fd = pfd
                            adapter.onWrite(
                                arrayOf(PageRange.ALL_PAGES), fd, CancellationSignal(),
                                object : PrintDocumentAdapter.WriteResultCallback() {
                                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                                        try { adapter.onFinish() } catch (_: Throwable) {}
                                        try { fd.close() } catch (_: Throwable) {}
                                        finish(true)
                                    }
                                    override fun onWriteFailed(error: CharSequence?) {
                                        try { fd.close() } catch (_: Throwable) {}
                                        log("  write failed: ${error ?: ""}")
                                        finish(false)
                                    }
                                }
                            )
                        } catch (t: Throwable) {
                            try { pfd?.close() } catch (_: Throwable) {}
                            log("  pdf error: ${t.message}")
                            finish(false)
                        }
                    }

                    override fun onLayoutFailed(error: CharSequence?) {
                        log("  layout failed: ${error ?: ""}")
                        finish(false)
                    }
                },
                null
            )
        } catch (t: Throwable) {
            log("  adapter error: ${t.message}")
            finish(false)
        }
    }
}
