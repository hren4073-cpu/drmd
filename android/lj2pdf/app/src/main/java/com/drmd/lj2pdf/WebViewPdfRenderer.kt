package com.drmd.lj2pdf

import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Renders web pages to PDF using a single offscreen [WebView] drawn straight
 * onto a [PdfDocument] canvas — no Chrome, and crucially no
 * PrintDocumentAdapter (whose result-callback constructors are package-private
 * and cannot be subclassed from app code).
 *
 * Pages are A4; long pages are split into multiple A4 pages. All WebView work
 * must happen on the main thread.
 */
class WebViewPdfRenderer(
    private val web: WebView,
    private val log: (String) -> Unit
) {
    // A4 at 72 dpi, in PostScript points.
    private val pageWidthPt = 595
    private val pageHeightPt = 842
    // Lay the page out wider than A4 for crisp text, then scale down to fit.
    private val renderWidthPx = 1080
    private val scale = pageWidthPt.toFloat() / renderWidthPx

    init {
        web.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // draw() only renders onto a software canvas if the layer is software.
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** Load [url], wait for it to settle, then write a PDF to [outFile]. */
    suspend fun renderUrlToPdf(url: String, outFile: File, settleMs: Long): Boolean {
        if (!loadPage(url)) return false
        delay(settleMs)        // let late resources / web fonts / images paint
        return drawToPdf(outFile)
    }

    private suspend fun loadPage(url: String): Boolean =
        suspendCancellableCoroutine { cont ->
            web.webViewClient = object : WebViewClient() {
                private var settled = false
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (!settled) { settled = true; if (cont.isActive) cont.resume(true) }
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame && !settled) {
                        settled = true
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            web.loadUrl(url)
        }

    private fun drawToPdf(outFile: File): Boolean {
        return try {
            // Lay the WebView out at full content height, fixed width.
            web.measure(
                View.MeasureSpec.makeMeasureSpec(renderWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val contentH = web.measuredHeight.coerceAtLeast(1)
            web.layout(0, 0, renderWidthPx, contentH)

            val pageHeightPx = (pageHeightPt / scale).toInt().coerceAtLeast(1)
            val pages = ((contentH + pageHeightPx - 1) / pageHeightPx).coerceAtLeast(1)

            val doc = PdfDocument()
            for (i in 0 until pages) {
                val info = PdfDocument.PageInfo
                    .Builder(pageWidthPt, pageHeightPt, i + 1).create()
                val page = doc.startPage(info)
                val c = page.canvas
                c.save()
                c.scale(scale, scale)
                c.translate(0f, (-i * pageHeightPx).toFloat())
                web.draw(c)
                c.restore()
                doc.finishPage(page)
            }
            FileOutputStream(outFile).use { doc.writeTo(it) }
            doc.close()
            true
        } catch (t: Throwable) {
            log("  pdf error: ${t.message}")
            false
        }
    }
}
