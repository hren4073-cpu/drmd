package com.drmd.lj2pdf

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stage 2 (EPUB): packages the downloaded HTML base into a single reflowable
 * `.epub` (one chapter per post + a nav/ncx table of contents). Pure
 * `java.util.zip` — no extra dependency. Reflowable means no fixed pages and no
 * white space. Chapter XHTML is generated in parallel, then streamed into the
 * zip.
 */
object EpubBuilder {

    private val MEDIA = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp"
    )

    /** Build [out] from [entries]' saved HTML. @return true on success. */
    suspend fun build(
        project: Project, entries: List<PostEntry>, out: File, fontSize: Int, parallelism: Int = 0
    ): Boolean {
        val usable = entries.filter { project.htmlReady(it.id) }
        if (usable.isEmpty()) return false

        val cores = Runtime.getRuntime().availableProcessors()
        val par = (if (parallelism > 0) parallelism else cores).coerceIn(1, cores.coerceAtLeast(1) * 2)
        // 1) Generate chapter XHTML in parallel (CPU-bound).
        val chapters = usable.mapIndexed { i, e -> i to e }.mapPar(par) { (i, e) ->
            if (ConvertBus.cancelRequested) return@mapPar null
            try {
                Chapter(
                    idx = i + 1,
                    entry = e,
                    xhtml = chapterXhtml(project.postHtml(e.id), e.title),
                    images = project.imgDir(e.id).listFiles()?.toList() ?: emptyList()
                )
            } catch (t: Throwable) {
                ConvertBus.log("[epub] FAIL ${e.id}: ${t.message}"); null
            }
        }.filterNotNull()
        if (chapters.isEmpty()) return false

        // 2) Stream the zip (mimetype first, stored/uncompressed).
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            stored(zos, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            deflated(zos, "META-INF/container.xml", CONTAINER.toByteArray())
            deflated(zos, "OEBPS/style.css", css(fontSize).toByteArray())

            val manifest = StringBuilder()
            val spine = StringBuilder()
            val navLis = StringBuilder()
            val ncxPoints = StringBuilder()

            for (ch in chapters) {
                if (ConvertBus.cancelRequested) return false
                val href = "chap%04d.xhtml".format(ch.idx)
                deflated(zos, "OEBPS/$href", ch.xhtml.toByteArray())
                manifest.append("""<item id="chap${ch.idx}" href="$href" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""<itemref idref="chap${ch.idx}"/>""").append('\n')
                val t = esc(ch.entry.title.ifBlank { "Пост ${ch.entry.id}" })
                navLis.append("""<li><a href="$href">$t</a></li>""").append('\n')
                ncxPoints.append(
                    """<navPoint id="np${ch.idx}" playOrder="${ch.idx}"><navLabel><text>$t</text></navLabel><content src="$href"/></navPoint>"""
                ).append('\n')
                // chapter images
                for (img in ch.images) {
                    val ext = img.extension.lowercase()
                    val media = MEDIA[ext] ?: continue
                    val ihref = "img/${ch.entry.id}/${img.name}"
                    deflated(zos, "OEBPS/$ihref", img.readBytes())
                    manifest.append("""<item id="img_${ch.idx}_${img.nameWithoutExtension}" href="$ihref" media-type="$media"/>""").append('\n')
                }
            }

            deflated(zos, "OEBPS/nav.xhtml", nav(navLis.toString()).toByteArray())
            deflated(zos, "OEBPS/toc.ncx", ncx(project.name, ncxPoints.toString()).toByteArray())
            deflated(zos, "OEBPS/content.opf", opf(project.name, manifest.toString(), spine.toString()).toByteArray())
        }
        ConvertBus.log("[epub] ${chapters.size} chapter(s) → ${out.name}")
        return out.length() > 0
    }

    private class Chapter(
        val idx: Int, val entry: PostEntry, val xhtml: String, val images: List<File>
    )

    /** Re-serialise a saved post HTML as a standalone XHTML chapter. */
    private fun chapterXhtml(html: File, title: String): String {
        val d: Document = Jsoup.parse(html, "UTF-8")
        d.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)        // self-closing tags
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false)
        val body = d.body()?.html() ?: ""
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta charset="utf-8"/><title>${esc(title)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head><body>
$body
</body></html>"""
    }

    // ---- boilerplate templates ----------------------------------------------

    private const val CONTAINER =
        """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private fun css(font: Int) =
        "body{font-family:Georgia,serif;font-size:${font}px;line-height:1.55;margin:1em}" +
            "img{max-width:100%;height:auto}h1{font-size:1.4em;line-height:1.25}" +
            "blockquote{border-left:3px solid #ccc;margin:1em 0;padding:0 1em;color:#444}a{color:#1a4c8b}"

    private fun nav(lis: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head>
<meta charset="utf-8"/><title>Содержание</title></head><body>
<nav epub:type="toc" id="toc"><h1>Содержание</h1><ol>
$lis
</ol></nav></body></html>"""

    private fun ncx(name: String, points: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="urn:lj2pdf:${esc(name)}"/></head>
<docTitle><text>${esc(name)}</text></docTitle>
<navMap>
$points
</navMap></ncx>"""

    private fun opf(name: String, manifest: String, spine: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">urn:lj2pdf:${esc(name)}</dc:identifier>
<dc:title>${esc(name)}</dc:title><dc:language>ru</dc:language>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="css" href="style.css" media-type="text/css"/>
$manifest
</manifest>
<spine toc="ncx">
$spine
</spine></package>"""

    // ---- zip helpers ----------------------------------------------------------

    private fun stored(zos: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zos.putNextEntry(e); zos.write(data); zos.closeEntry()
    }

    private fun deflated(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        zos.write(data); zos.closeEntry()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
