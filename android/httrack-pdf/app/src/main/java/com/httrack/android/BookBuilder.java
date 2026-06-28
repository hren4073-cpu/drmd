package com.httrack.android;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges per-page PDFs into a single book and adds a clickable table of
 * contents (one bookmark per chapter, pointing at the chapter's first page).
 * Bookmark titles may contain Cyrillic; PDFBox encodes them as UTF-16.
 */
final class BookBuilder {
  private BookBuilder() {}

  static boolean mergeWithToc(final List<File> pages, final List<String> titles,
                              final File out) throws Exception {
    final List<File> usable = new ArrayList<File>();
    final List<String> usableTitles = new ArrayList<String>();
    for (int i = 0; i < pages.size(); i++) {
      final File f = pages.get(i);
      if (f != null && f.exists() && f.length() > 0) {
        usable.add(f);
        usableTitles.add(i < titles.size() ? titles.get(i) : ("Page " + (i + 1)));
      }
    }
    if (usable.isEmpty()) {
      return false;
    }

    final File parent = out.getParentFile();
    final File tmp = new File(parent, ".merged_tmp.pdf");

    // 1) Record each chapter's page count, then merge the raw PDFs.
    final int[] counts = new int[usable.size()];
    final PDFMergerUtility merger = new PDFMergerUtility();
    for (int i = 0; i < usable.size(); i++) {
      final PDDocument d = PDDocument.load(usable.get(i));
      try {
        counts[i] = d.getNumberOfPages();
      } finally {
        d.close();
      }
      merger.addSource(usable.get(i));
    }
    merger.setDestinationFileName(tmp.getAbsolutePath());
    merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly());

    // 2) Re-open the merged file and attach the outline / TOC.
    final PDDocument doc = PDDocument.load(tmp);
    try {
      final PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);

      int pageStart = 0;
      for (int i = 0; i < usable.size(); i++) {
        if (pageStart >= doc.getNumberOfPages()) {
          break;
        }
        final PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
        dest.setPage(doc.getPage(pageStart));

        final PDOutlineItem item = new PDOutlineItem();
        item.setTitle(usableTitles.get(i));
        item.setDestination(dest);
        outline.addLast(item);

        pageStart += Math.max(1, counts[i]);
      }
      outline.openNode();
      doc.save(out);
    } finally {
      doc.close();
    }

    tmp.delete();
    return out.exists() && out.length() > 0;
  }
}
