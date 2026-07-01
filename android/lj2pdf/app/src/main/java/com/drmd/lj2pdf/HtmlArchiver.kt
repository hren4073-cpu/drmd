package com.drmd.lj2pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 1 of the pipeline: download each post's server HTML over HTTP (many in
 * parallel), extract the article + title with jsoup, pull its images down too,
 * and write a self-contained reader HTML to `html/<id>.html` (img src rewritten
 * to a local `img/<id>/…` path). PDF / EPUB / RAG all build from these files.
 */
object HtmlArchiver {

    private const val IMG_PAR = 4          // images per post fetched concurrently

    // Content container, best-match first (LJ, Habr, osnova, generic).
    private val CONTENT_SELECTORS = listOf(
        ".b-singlepost-body", ".aentry-post__text", ".entry-content", "#entrytext",
        ".entry-text", ".b-singlepost-bodytext", ".article-formatted-body",
        ".tm-article-body", ".post__text", "article", ".asset-body", ".entry"
    )
    private val TITLE_SELECTORS = listOf(
        "h1.entry-title", ".b-singlepost-title", ".entry-title", ".j-e-title",
        ".tm-title", "article h1", "h1", "h2"
    )
    // Junk to strip from the content node before saving.
    private const val STRIP =
        "script,style,noscript,iframe,form,svg,button,ins.adsbygoogle," +
            "[id*=google_ads],[class*=advert],[class*=banner],[id*=banner]," +
            "[class*=promo],[id*=promo],[class*=adfox],.lj-promo,.ljad," +
            ".appwidget-ljad,.lj-app-banner,.b-popup,.adv,.ads,nav,header,footer," +
            ".comments,.b-singlepost-tools,.b-singlepost-controls,.b-singlepost-addcomment"

    /**
     * Download [permalinks] into [project]'s html base. Returns the post entries
     * (id, permalink, title) that are now available on disk.
     */
    suspend fun download(
        project: Project, permalinks: List<String>,
        parallelism: Int = 8, imgOn: Boolean = true, imgMax: Int = 0,
        contentSel: String = "",
        tr: Translator.Config = Translator.Config(false, "ru", "", "", "libre")
    ): List<PostEntry> {
        val total = permalinks.size
        val done = AtomicInteger(0)
        if (tr.active) ConvertBus.log("[html] translation ON → ${tr.target} (slower)")
        ConvertBus.log("[html] downloading $total post(s) on ${parallelism.coerceIn(1, 32)} threads…")
        val entries = permalinks.mapPar(parallelism.coerceIn(1, 32)) { perma ->
            if (ConvertBus.cancelRequested) return@mapPar null
            val id = Projects.idOf(perma)
            val entry = try {
                if (project.htmlReady(id)) reuse(project, id, perma)
                else fetchOne(project, id, perma, imgOn, imgMax, contentSel, tr)
            } catch (t: Throwable) {
                ConvertBus.log("[html] FAIL $perma: ${t.message}"); null
            }
            val n = done.incrementAndGet()
            ConvertBus.progress(n, total, "Downloading $n/$total")
            entry
        }.filterNotNull()
        ConvertBus.log("[html] base ready: ${entries.size}/$total post(s)")
        return entries
    }

    private fun reuse(project: Project, id: String, perma: String): PostEntry {
        val title = try { Jsoup.parse(project.postHtml(id), "UTF-8").title() } catch (_: Throwable) { "" }
        return PostEntry(id, perma, title.ifBlank { "Пост $id" })
    }

    private suspend fun fetchOne(
        project: Project, id: String, perma: String, imgOn: Boolean, imgMax: Int,
        contentSel: String, tr: Translator.Config
    ): PostEntry? {
        val doc = Http.doc(perma) ?: return null
        var title = TITLE_SELECTORS.firstNotNullOfOrNull { sel ->
            doc.selectFirst(sel)?.text()?.trim()?.ifBlank { null }
        } ?: doc.title().trim().ifBlank { "Пост $id" }

        val content = (if (contentSel.isNotBlank()) doc.selectFirst(contentSel) else null)
            ?: CONTENT_SELECTORS.firstNotNullOfOrNull { sel ->
                doc.selectFirst(sel)?.takeIf { it.text().trim().length > 20 }
            } ?: doc.body() ?: return null

        content.select(STRIP).remove()
        if (imgOn) downloadImages(project, id, content, imgMax)
        else content.select("img").remove()

        if (tr.active) {
            title = Translator.translate(tr, title)
            translateBlocks(content, tr)
        }

        project.postHtml(id).writeText(wrap(title, content.html()))
        return PostEntry(id, perma, title)
    }

    /** Translate text-only block elements in place (keeps images/structure). */
    private suspend fun translateBlocks(content: Element, tr: Translator.Config) {
        val blocks = content.select("p,h1,h2,h3,h4,h5,li,blockquote,figcaption")
            .toList()
            .filter { it.select("img").isEmpty() && it.text().isNotBlank() }
        blocks.mapPar(2) { el ->
            if (!ConvertBus.cancelRequested) {
                val t = Translator.translate(tr, el.text())
                synchronized(content) { el.text(t) }
            }
        }
    }

    /** Download every <img> in [content] (in parallel) and rewrite src locally. */
    private suspend fun downloadImages(project: Project, id: String, content: Element, imgMax: Int) {
        val imgs = content.select("img")
        if (imgs.isEmpty()) return
        val urls = imgs.map { img ->
            img.absUrl("src").ifBlank { img.absUrl("data-src") }.ifBlank { img.absUrl("data-original") }
        }
        val saved = urls.mapIndexedPar(IMG_PAR) { i, url ->
            if (url.isBlank() || ConvertBus.cancelRequested) null
            else Http.getBytes(url)?.let { saveImage(project, id, i, url, it, imgMax) }
        }
        imgs.forEachIndexed { i, img ->
            val rel = saved[i]
            if (rel != null) {
                img.attr("src", rel)
                img.removeAttr("srcset"); img.removeAttr("data-src"); img.removeAttr("data-original")
            } else {
                img.remove()
            }
        }
    }

    /** Save image bytes; return the path relative to the html file (img/<id>/n.ext). */
    private fun saveImage(project: Project, id: String, idx: Int, url: String, bytes: ByteArray, imgMax: Int): String? {
        if (bytes.size < 64) return null              // 1x1 trackers / empties
        // Optional downscale to bound storage / speed EPUB (max dimension imgMax).
        if (imgMax in 1..6000) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                val big = maxOf(b.outWidth, b.outHeight)
                if (big > imgMax && b.outWidth > 0) {
                    var sample = 1
                    while (big / sample > imgMax * 2) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (bmp != null) {
                        val scale = imgMax.toFloat() / maxOf(bmp.width, bmp.height)
                        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                        val f = File(project.imgDir(id), "$idx.jpg")
                        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        return "img/$id/$idx.jpg"
                    }
                }
            } catch (_: Throwable) { /* fall through to raw write */ }
        }
        val ext = when {
            url.contains(".png", true) -> "png"
            url.contains(".gif", true) -> "gif"
            url.contains(".webp", true) -> "webp"
            else -> "jpg"
        }
        val f = File(project.imgDir(id), "$idx.$ext")
        f.writeBytes(bytes)
        return "img/$id/$idx.$ext"
    }

    private fun wrap(title: String, body: String): String =
        "<!doctype html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<title>${esc(title)}</title>\n<style>\n" +
            "body{font-family:Georgia,'PT Serif',serif;font-size:18px;line-height:1.55;" +
            "margin:24px;color:#111;background:#fff;word-wrap:break-word}\n" +
            "img{max-width:100%;height:auto;display:block;margin:12px auto}\n" +
            "h1{font-size:1.5em;line-height:1.25;margin:0 0 .6em}\n" +
            "blockquote{border-left:3px solid #ccc;margin:1em 0;padding:0 1em;color:#444}\n" +
            "a{color:#1a4c8b}\n</style></head>\n<body>\n<h1>${esc(title)}</h1>\n$body\n</body></html>"

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/** Indexed parallel map (≤[n] concurrent), order preserved. */
suspend fun <R> List<String>.mapIndexedPar(n: Int, f: suspend (Int, String) -> R): List<R> =
    withIndex().toList().mapPar(n) { (i, v) -> f(i, v) }
