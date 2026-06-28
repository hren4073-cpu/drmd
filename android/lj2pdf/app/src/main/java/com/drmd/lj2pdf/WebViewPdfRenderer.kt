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
 * Renders web pages to PDF by drawing an offscreen [WebView] straight onto a
 * [PdfDocument] canvas — no Chrome, no PrintDocumentAdapter.
 *
 * Extras for blog archiving:
 *  - optional in-page CLEANING (removes ads/promo/banners via injected JS),
 *  - entry detection (counts post permalinks) so the caller can walk a blog
 *    "to the last page" and stop automatically when a page has no entries.
 *
 * All WebView work must happen on the main thread.
 */
class WebViewPdfRenderer(
    private val web: WebView,
    private val log: (String) -> Unit
) {
    /** ok: PDF written; signature: post permalinks on the page; count: their number. */
    data class RenderResult(val ok: Boolean, val signature: String, val count: Int)

    private val pageWidthPt = 595          // A4 @72dpi
    private val pageHeightPt = 842
    private val renderWidthPx = 1080        // render wide, scale down for crisp text
    private val scale = pageWidthPt.toFloat() / renderWidthPx

    init {
        web.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Load [url], optionally clean it, count entries, and (unless the page is
     * empty while [requireEntries]) draw it to [outFile].
     */
    suspend fun render(
        url: String, outFile: File, settleMs: Long,
        clean: Boolean, requireEntries: Boolean
    ): RenderResult {
        if (!loadPage(url)) return RenderResult(false, "", -1)
        delay(settleMs)
        if (clean) {
            evalJs(CLEAN_JS)
            delay(250)
        }
        val signature = evalJs(COUNT_JS).trim().removeSurrounding("\"")
        val count = if (signature.isEmpty()) 0 else signature.split(',').size
        if (requireEntries && count == 0) {
            return RenderResult(false, "", 0)   // past the last page -> stop
        }
        val ok = drawToPdf(outFile)
        return RenderResult(ok, signature, count)
    }

    /** Simple variant for non-blog modes (single page / template). */
    suspend fun renderUrlToPdf(url: String, outFile: File, settleMs: Long, clean: Boolean): Boolean =
        render(url, outFile, settleMs, clean, requireEntries = false).ok

    private suspend fun loadPage(url: String): Boolean =
        suspendCancellableCoroutine { cont ->
            web.webViewClient = object : WebViewClient() {
                private var settled = false
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (!settled) { settled = true; if (cont.isActive) cont.resume(true) }
                }
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame && !settled) {
                        settled = true
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            web.loadUrl(url)
        }

    private suspend fun evalJs(script: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                web.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value ?: "")
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume("")
            }
        }

    private fun drawToPdf(outFile: File): Boolean {
        return try {
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
                val info = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, i + 1).create()
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

    companion object {
        /** Remove ads / promo / banners and force a clean white background. */
        private const val CLEAN_JS = """
(function(){
  try{
    var sel = ['ins.adsbygoogle','[id*="google_ads"]','[id^="ad-"]','[id*="adfox"]',
      '[class*="adfox"]','[class*="advert"]','[class*="-ad-"]','[class*="banner"]',
      '[id*="banner"]','[data-ad]','iframe[src*="ad"]','iframe[src*="banner"]',
      '.lj-promo','.ljad','.appwidget-ljad','.lj-app-banner','.b-popup',
      '[class*="promo"]','[id*="promo"]','.adv','.ads','[class*="yandex_ad"]'];
    sel.forEach(function(s){
      var n=document.querySelectorAll(s);
      for(var i=0;i<n.length;i++){ if(n[i]&&n[i].parentNode) n[i].parentNode.removeChild(n[i]); }
    });
    var st=document.createElement('style');
    st.innerHTML='body{background:#fff!important}'+
      'iframe[src*="ad"],iframe[src*="banner"],ins.adsbygoogle{display:none!important}';
    (document.head||document.documentElement).appendChild(st);
    return 'ok';
  }catch(e){ return 'err'; }
})();
"""

        /** Distinct post permalinks (host/<digits>.html) -> entry signature. */
        private const val COUNT_JS = """
(function(){
  try{
    var host=location.host, a=document.querySelectorAll('a[href*=".html"]'), seen={};
    for(var i=0;i<a.length;i++){
      var h=a[i].href||'';
      var m=h.match(/^https?:\/\/([^\/]+)\/(\d+)\.html/);
      if(m && m[1].indexOf(host)!==-1) seen[m[2]]=1;
    }
    return Object.keys(seen).sort().join(',');
  }catch(e){ return ''; }
})();
"""
    }
}
