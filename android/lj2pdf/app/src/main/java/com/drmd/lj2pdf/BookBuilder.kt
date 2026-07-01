package com.drmd.lj2pdf

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * Merges per-page PDFs into a single book and prepends an automatic
 * "Содержание" (Table of Contents) page: each chapter is listed as
 * "title …… N" where N is the page number, and the whole row is a clickable
 * hyperlink that jumps to the chapter's first page. The same chapters are also
 * added to the PDF outline (the reader's bookmark sidebar).
 *
 * A bundled Cyrillic TTF (DejaVuSans) is embedded so Russian titles render
 * correctly — PDFBox's built-in fonts cannot encode Cyrillic.
 */
object BookBuilder {

    // A4-ish page geometry (points).
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private const val MARGIN = 50f
    private const val TITLE_SIZE = 20f
    private const val ENTRY_SIZE = 11f
    private const val LINE_H = 20f

    private val linkBlue = PDColor(floatArrayOf(0.10f, 0.30f, 0.65f), PDDeviceRGB.INSTANCE)

    /** @return true on success; [out] then contains the finished book.pdf. */
    fun mergeWithToc(
        ctx: Context, pages: List<File>, titles: List<String>, out: File,
        heading: String = "Содержание"
    ): Boolean {
        val usable = pages.filter { it.exists() && it.length() > 0 }
        if (usable.isEmpty()) return false

        val parent = out.parentFile ?: return false
        val tmp = File(parent, ".merged_tmp.pdf")

        // 1) Record each chapter's page count, then merge the raw PDFs.
        val counts = IntArray(usable.size)
        val merger = PDFMergerUtility()
        for ((i, f) in usable.withIndex()) {
            PDDocument.load(f).use { d -> counts[i] = d.numberOfPages }
            merger.addSource(f)
        }
        merger.destinationFileName = tmp.absolutePath
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())

        // 2) Re-open the merged file, build the TOC and attach the outline.
        PDDocument.load(tmp).use { doc ->
            // Chapter → first page index within the *merged* (content-only) doc.
            val starts = IntArray(usable.size)
            var acc = 0
            for (i in usable.indices) {
                starts[i] = acc
                acc += counts[i].coerceAtLeast(1)
            }
            val n = minOf(usable.size, doc.numberOfPages)

            // Load embedded Cyrillic fonts (fall back to Helvetica if missing).
            val regular: PDFont = loadFont(ctx, doc, "DejaVuSans.ttf") ?: PDType1Font.HELVETICA
            val bold: PDFont = loadFont(ctx, doc, "DejaVuSans-Bold.ttf")
                ?: loadFont(ctx, doc, "DejaVuSans.ttf") ?: PDType1Font.HELVETICA_BOLD

            // Pre-sanitize titles to characters the font can actually encode.
            val safeTitles = (0 until n).map {
                sanitize(regular, titles.getOrElse(it) { "Глава ${it + 1}" }.ifBlank { "Глава ${it + 1}" })
            }

            // 3) Lay entries out across TOC pages (heading only on the first).
            val layout = layoutToc(n)
            val tocCount = layout.size

            // 4) Insert blank TOC pages at the very front, keeping their order.
            val firstContent = doc.getPage(0)
            val tocPages = ArrayList<PDPage>(tocCount)
            repeat(tocCount) { tocPages.add(PDPage(PDRectangle(PAGE_W, PAGE_H))) }
            for (i in tocCount - 1 downTo 0) doc.pages.insertBefore(tocPages[i], firstContent)

            // Final page index of chapter i = tocCount + its content start.
            // Human page number (1-based) shown in the TOC.
            // 5) Draw each TOC page and wire up the clickable links.
            for ((pageIdx, rows) in layout.withIndex()) {
                val tp = tocPages[pageIdx]
                PDPageContentStream(doc, tp).use { cs ->
                    var y = PAGE_H - MARGIN
                    if (pageIdx == 0) {
                        cs.beginText()
                        cs.setFont(bold, TITLE_SIZE)
                        cs.newLineAtOffset(MARGIN, y - TITLE_SIZE)
                        cs.showText(sanitize(bold, heading))
                        cs.endText()
                        y -= (TITLE_SIZE + 18f)
                    }
                    for (entry in rows) {
                        y -= LINE_H
                        val target = tocCount + starts[entry]
                        val pageNumStr = (target + 1).toString()
                        drawEntry(doc, tp, cs, regular, bold, safeTitles[entry], pageNumStr, y, target)
                    }
                }
            }

            // 6) Outline / bookmark sidebar, pointing at the shifted pages.
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            for (i in 0 until n) {
                val target = tocCount + starts[i]
                if (target >= doc.numberOfPages) break
                val dest = PDPageFitWidthDestination().apply { page = doc.getPage(target) }
                outline.addLast(PDOutlineItem().apply {
                    title = titles.getOrElse(i) { "Глава ${i + 1}" }
                    destination = dest
                })
            }
            outline.openNode()
            doc.documentCatalog.pageMode = com.tom_roush.pdfbox.pdmodel.PageMode.USE_OUTLINES

            doc.save(out)
        }

        tmp.delete()
        return out.exists() && out.length() > 0
    }

    /** Draw one "title …… page" row and overlay a GoTo hyperlink on it. */
    private fun drawEntry(
        doc: PDDocument, page: PDPage, cs: PDPageContentStream,
        font: PDFont, bold: PDFont, title: String, pageNumStr: String,
        baselineY: Float, targetPageIdx: Int
    ) {
        val leftX = MARGIN
        val rightX = PAGE_W - MARGIN
        val numW = textWidth(font, pageNumStr, ENTRY_SIZE)
        val gap = 6f
        val maxTitleW = rightX - leftX - numW - 2 * gap - 14f  // 14 ≈ room for dots
        val shown = truncate(font, title, ENTRY_SIZE, maxTitleW)
        val titleW = textWidth(font, shown, ENTRY_SIZE)

        // Title (link-blue), left-aligned.
        cs.beginText()
        cs.setFont(font, ENTRY_SIZE)
        cs.setNonStrokingColor(linkBlue)
        cs.newLineAtOffset(leftX, baselineY)
        cs.showText(shown)
        cs.endText()

        // Page number, right-aligned.
        cs.beginText()
        cs.setFont(font, ENTRY_SIZE)
        cs.newLineAtOffset(rightX - numW, baselineY)
        cs.showText(pageNumStr)
        cs.endText()

        // Dot leaders between title and number.
        val dotsStart = leftX + titleW + gap
        val dotsEnd = rightX - numW - gap
        if (dotsEnd > dotsStart) {
            val dotW = textWidth(font, ".", ENTRY_SIZE)
            val count = ((dotsEnd - dotsStart) / dotW).toInt().coerceAtMost(400)
            if (count > 0) {
                cs.beginText()
                cs.setFont(font, ENTRY_SIZE)
                cs.newLineAtOffset(dotsStart, baselineY)
                cs.showText(".".repeat(count))
                cs.endText()
            }
        }
        cs.setNonStrokingColor(0f, 0f, 0f)

        // Clickable link rectangle covering the whole row.
        val link = PDAnnotationLink()
        link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
        link.rectangle = PDRectangle(leftX, baselineY - 4f, rightX - leftX, LINE_H)
        val dest = PDPageXYZDestination().apply {
            this.page = doc.getPage(targetPageIdx)
            top = PAGE_H.toInt()
        }
        link.action = PDActionGoTo().apply { destination = dest }
        page.annotations.add(link)
    }

    /** Distribute n entries across TOC pages; returns rows-per-page. */
    private fun layoutToc(n: Int): List<List<Int>> {
        val firstCap = ((PAGE_H - 2 * MARGIN - (TITLE_SIZE + 18f)) / LINE_H).toInt().coerceAtLeast(1)
        val restCap = ((PAGE_H - 2 * MARGIN) / LINE_H).toInt().coerceAtLeast(1)
        val pages = ArrayList<List<Int>>()
        var i = 0
        var first = true
        while (i < n) {
            val cap = if (first) firstCap else restCap
            val end = minOf(i + cap, n)
            pages.add((i until end).toList())
            i = end
            first = false
        }
        return pages
    }

    private fun loadFont(ctx: Context, doc: PDDocument, asset: String): PDFont? = try {
        ctx.assets.open(asset).use { PDType0Font.load(doc, it, true) }
    } catch (_: Throwable) { null }

    private fun textWidth(font: PDFont, s: String, size: Float): Float = try {
        font.getStringWidth(s) / 1000f * size
    } catch (_: Throwable) { s.length * size * 0.5f }

    private fun truncate(font: PDFont, s: String, size: Float, maxW: Float): String {
        if (textWidth(font, s, size) <= maxW) return s
        var str = s
        while (str.isNotEmpty() && textWidth(font, "$str…", size) > maxW) {
            str = str.substring(0, str.length - 1)
        }
        return "$str…"
    }

    /** Keep only characters the embedded font can encode (avoids crashes). */
    private val glyphCache = HashMap<Int, Boolean>()
    private fun sanitize(font: PDFont, s: String): String {
        if (font is PDType1Font) {
            // Standard 14 fonts: strip to Latin-1 to stay safe.
            return buildString { s.forEach { if (it.code in 32..255) append(it) } }.ifBlank { "?" }
        }
        val sb = StringBuilder(s.length)
        var idx = 0
        while (idx < s.length) {
            val cp = s.codePointAt(idx)
            val cc = Character.charCount(cp)
            val str = s.substring(idx, idx + cc)
            val ok = glyphCache.getOrPut(cp) {
                try { font.getStringWidth(str); true } catch (_: Throwable) { false }
            }
            if (ok) sb.append(str)
            idx += cc
        }
        return sb.toString().ifBlank { "?" }
    }
}
