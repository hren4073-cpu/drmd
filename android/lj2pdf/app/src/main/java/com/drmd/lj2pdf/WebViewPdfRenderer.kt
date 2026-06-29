package com.drmd.lj2pdf

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
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
 * Renders web pages to PDF by drawing an offscreen [WebView] onto a
 * [PdfDocument] canvas — no Chrome, no PrintDocumentAdapter.
 *
 * The renderer OWNS its WebView and recreates it if the WebView's renderer
 * process dies ([WebViewClient.onRenderProcessGone]) — otherwise Android kills
 * the whole app, which was crashing long scans of media-heavy blogs.
 *
 * All methods must run on the main thread.
 */
class WebViewPdfRenderer(
    private val ctx: Context,
    private val log: (String) -> Unit
) {
    data class RenderResult(
        val ok: Boolean, val signature: String, val count: Int, val title: String
    )

    private val pageWidthPt = 595
    private val pageHeightPt = 842
    private val renderWidthPx = 1080
    private val scale = pageWidthPt.toFloat() / renderWidthPx

    @Volatile private var dead = false
    private var loads = 0
    private val recreateEvery = 8       // refresh the WebView often to bound memory
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var attached: WebView? = null
    private var web: WebView = newWeb()

    private fun newWeb(): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            blockNetworkImage = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        w.setLayerType(View.LAYER_TYPE_SOFTWARE, null)   // draw() needs software layer
        attachOverlay(w)
        return w
    }

    /**
     * Give the WebView a real (1×1, invisible) window via WindowManager so it
     * keeps rendering when the app is in the background — without this an
     * off-screen WebView is throttled/blank and progress stalls on minimise.
     * Needs the "draw over other apps" permission; falls back gracefully.
     */
    private fun attachOverlay(w: WebView) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.alpha = 0f                       // fully transparent: invisible to the user
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(w, lp)
            attached = w
        } catch (t: Throwable) {
            attached = null
            log("  (overlay unavailable — background rendering may stall: ${t.message})")
        }
    }

    private fun detachOverlay() {
        try { attached?.let { wm.removeView(it) } } catch (_: Throwable) {}
        attached = null
    }

    /** Recreate the WebView if it died, or periodically to bound memory. */
    private fun ensureAlive() {
        if (dead || loads >= recreateEvery) {
            detachOverlay()
            try { web.destroy() } catch (_: Throwable) {}
            web = newWeb()
            dead = false
            loads = 0
        }
    }

    fun destroy() {
        detachOverlay()
        try { web.destroy() } catch (_: Throwable) {}
    }

    /** Load a list page and return its distinct post permalinks (in order). */
    suspend fun collectPostLinks(url: String, settleMs: Long): List<String> {
        ensureAlive()
        if (!loadPage(url)) return emptyList()
        loads++
        delay(settleMs)
        val raw = jsUnquote(evalJs(LINKS_JS))
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Load a page and return all same-host links (for the calendar/archive). */
    suspend fun collectAllLinks(url: String, settleMs: Long): List<String> {
        ensureAlive()
        if (!loadPage(url)) return emptyList()
        loads++
        delay(settleMs)
        val raw = jsUnquote(evalJs(ALL_LINKS_JS))
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun render(
        url: String, outFile: File, settleMs: Long,
        clean: Boolean, requireEntries: Boolean
    ): RenderResult {
        ensureAlive()
        if (!loadPage(url)) return RenderResult(false, "", -1, "")
        loads++
        delay(settleMs)
        if (clean) { evalJs(CLEAN_JS); delay(250) }

        val title = jsUnquote(evalJs("document.title"))
        val signature = jsUnquote(evalJs(COUNT_JS))
        val count = if (signature.isEmpty()) 0 else signature.split(',').size
        if (requireEntries && count == 0) return RenderResult(false, "", 0, title)

        val ok = drawToPdf(outFile)
        return RenderResult(ok, signature, count, title)
    }

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
                override fun onRenderProcessGone(
                    view: WebView?, detail: RenderProcessGoneDetail?
                ): Boolean {
                    // The WebView's renderer died; recover instead of crashing.
                    dead = true
                    log("  webview renderer gone — recovering")
                    if (!settled) { settled = true; if (cont.isActive) cont.resume(false) }
                    return true
                }
            }
            try {
                web.loadUrl(url)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(false)
            }
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

    /**
     * Render the loaded page to a multi-page A4 PDF using SCROLL-AND-DRAW:
     * the WebView is laid out only one page tall (so the software-layer bitmap
     * is small) and scrolled page by page. Laying it out at the full content
     * height would allocate a giant bitmap and OOM-crash on long posts.
     */
    private fun drawToPdf(outFile: File): Boolean {
        val maxPages = 800
        val widthSpec = View.MeasureSpec.makeMeasureSpec(renderWidthPx, View.MeasureSpec.EXACTLY)
        val pageHeightPx = (pageHeightPt / scale).toInt().coerceAtLeast(1)
        var doc: PdfDocument? = null
        var fos: FileOutputStream? = null
        return try {
            // 1) Measure full content height (measurement only — no big allocation).
            web.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            var fullH = web.measuredHeight.coerceAtLeast(1)
            if (fullH > pageHeightPx * maxPages) {
                log("  page very long — capped at $maxPages pages")
                fullH = pageHeightPx * maxPages
            }
            val pages = ((fullH + pageHeightPx - 1) / pageHeightPx).coerceAtLeast(1)

            // 2) Lay the view out ONE page tall and scroll through the content.
            web.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(pageHeightPx, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, renderWidthPx, pageHeightPx)

            doc = PdfDocument()
            for (i in 0 until pages) {
                web.scrollTo(0, i * pageHeightPx)
                val info = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, i + 1).create()
                val page = doc.startPage(info)
                val c = page.canvas
                c.save()
                c.scale(scale, scale)
                web.draw(c)            // draws the current viewport slice
                c.restore()
                doc.finishPage(page)
            }
            fos = FileOutputStream(outFile)
            doc.writeTo(fos)
            true
        } catch (t: Throwable) {
            log("  pdf error: ${t.message}")
            false
        } finally {
            try { fos?.close() } catch (_: Throwable) {}
            try { doc?.close() } catch (_: Throwable) {}
            try { web.scrollTo(0, 0) } catch (_: Throwable) {}
        }
    }

    private fun jsUnquote(value: String): String {
        var s = value.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { i += 2 }
                    'u' -> {
                        if (i + 6 <= s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            i += 6
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    companion object {
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

        private const val ALL_LINKS_JS = """
(function(){
  try{
    var host=location.host, a=document.querySelectorAll('a[href]'), out=[], seen={};
    for(var i=0;i<a.length;i++){
      var h=a[i].href||'';
      if(h.indexOf('://')<0) continue;
      var hh=(h.split('/')[2]||'');
      if(hh.indexOf(host)<0) continue;
      if(!seen[h]){ seen[h]=1; out.push(h); }
    }
    return out.join('\n');
  }catch(e){ return ''; }
})();
"""

        private const val LINKS_JS = """
(function(){
  try{
    var host=location.host, a=document.querySelectorAll('a[href*=".html"]'), out=[], seen={};
    for(var i=0;i<a.length;i++){
      var h=a[i].href||'';
      var m=h.match(/^(https?:\/\/([^\/]+)\/(\d+))\.html/);
      if(m && m[2].indexOf(host)!==-1){
        var u=m[1]+'.html';
        if(!seen[u]){ seen[u]=1; out.push(u); }
      }
    }
    return out.join('\n');
  }catch(e){ return ''; }
})();
"""
    }
}
