package com.drmd.lj2pdf

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2 (PDF): renders each saved `html/<id>.html` to `posts/<id>.pdf` using
 * a pure StaticLayout text engine — no WebView, so many posts render in parallel
 * on the CPU. Local images are decoded inline (no network → no blank pages).
 * The per-post PDFs are then merged into тома by [BookBuilder].
 */
object PdfRenderer2 {

    private const val PAGE_W = 595         // A4 points
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val CONTENT_H = PAGE_H - 2 * MARGIN

    /**
     * Render every entry's HTML to a per-post PDF, in parallel. Returns the
     * entries that produced a non-empty PDF (so the caller merges only those).
     */
    suspend fun renderAll(
        project: Project, entries: List<PostEntry>, fontSize: Float
    ): List<PostEntry> = withContext(Dispatchers.Default) {
        val total = entries.size
        val done = AtomicInteger(0)
        val par = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        ConvertBus.log("[pdf] rendering $total post(s) on $par threads…")
        val ok = entries.mapPar(par) { e ->
            if (ConvertBus.cancelRequested) return@mapPar null
            val html = project.postHtml(e.id)
            val pdf = project.postPdf(e.id)
            val result = try {
                if (html.exists() && html.length() > 0 && renderOne(project, html, e, fontSize, pdf)) e
                else null
            } catch (t: Throwable) {
                ConvertBus.log("[pdf] FAIL ${e.id}: ${t.message}"); null
            }
            val n = done.incrementAndGet()
            ConvertBus.progress(n, total, "Rendering $n/$total")
            result
        }.filterNotNull()
        ConvertBus.log("[pdf] rendered ${ok.size}/$total post(s)")
        ok
    }

    private fun renderOne(
        project: Project, html: File, entry: PostEntry, fontSize: Float, out: File
    ): Boolean {
        val raw = html.readText()
        val imageGetter = Html.ImageGetter { src -> loadImage(project, html, src) }
        val spanned = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, null)

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.rgb(0x11, 0x11, 0x11)
            textSize = fontSize
        }
        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            spanned, paint, CONTENT_W, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, true
        )

        // Page breaks at line boundaries so text lines are never cut in half.
        val breaks = ArrayList<Int>()
        breaks.add(0)
        var line = 0
        val n = layout.lineCount
        while (line < n) {
            val pageTop = layout.getLineTop(line)
            var last = line
            while (last < n && layout.getLineBottom(last) - pageTop <= CONTENT_H) last++
            if (last == line) last = line + 1            // a single line taller than a page
            line = last
            breaks.add(if (line < n) layout.getLineTop(line) else layout.height)
        }

        val doc = PdfDocument()
        try {
            for (p in 0 until breaks.size - 1) {
                val topY = breaks[p]
                val botY = breaks[p + 1]
                val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, p + 1).create()
                val page = doc.startPage(info)
                val c = page.canvas
                c.save()
                c.translate(MARGIN.toFloat(), MARGIN.toFloat())
                c.clipRect(0, 0, CONTENT_W, (botY - topY).coerceAtMost(CONTENT_H))
                c.translate(0f, -topY.toFloat())
                layout.draw(c)
                c.restore()
                doc.finishPage(page)
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return out.length() > 0
    }

    /** Decode a local image (downscaled to content width) for inline layout. */
    private fun loadImage(project: Project, html: File, src: String): Drawable? {
        return try {
            val file = if (src.startsWith("img/")) File(html.parentFile, src) else File(src)
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > CONTENT_W * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val scale = (CONTENT_W.toFloat() / bmp.width).coerceAtMost(1f)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            BitmapDrawable(null, bmp).apply { setBounds(0, 0, w, h) }
        } catch (_: Throwable) {
            null
        }
    }
}
