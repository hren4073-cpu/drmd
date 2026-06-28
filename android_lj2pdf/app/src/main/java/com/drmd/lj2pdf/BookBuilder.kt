package com.drmd.lj2pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * Merges per-page PDFs into a single book and adds a clickable table of
 * contents (one bookmark per chapter, pointing at the chapter's first page).
 * Bookmark titles may contain Cyrillic — PDFBox encodes them as UTF-16.
 */
object BookBuilder {

    /** @return true on success; [out] then contains the finished book.pdf. */
    fun mergeWithToc(pages: List<File>, titles: List<String>, out: File): Boolean {
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

        // 2) Re-open the merged file and attach the outline / TOC.
        PDDocument.load(tmp).use { doc ->
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline

            var pageStart = 0
            for (i in usable.indices) {
                if (pageStart >= doc.numberOfPages) break
                val dest = PDPageFitWidthDestination().apply {
                    page = doc.getPage(pageStart)
                }
                val item = PDOutlineItem().apply {
                    title = titles.getOrElse(i) { "Page ${i + 1}" }
                    destination = dest
                }
                outline.addLast(item)
                pageStart += counts[i].coerceAtLeast(1)
            }
            outline.openNode()
            doc.save(out)
        }

        tmp.delete()
        return out.exists() && out.length() > 0
    }
}
