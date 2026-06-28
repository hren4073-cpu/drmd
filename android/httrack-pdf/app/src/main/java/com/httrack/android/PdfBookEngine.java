package com.httrack.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI-agnostic "mirror folder -> book.pdf" pipeline.
 *
 * Renders every saved HTML page of a finished HTTrack mirror to PDF by drawing
 * the WebView straight onto a {@link PdfDocument} canvas (no Chrome, and no
 * PrintDocumentAdapter — its result-callback constructors are package-private
 * and cannot be subclassed). Long pages are split across A4 pages. Then the
 * per-page PDFs are merged into one book with a TOC (PDFBox).
 *
 * All WebView work happens on the main thread; the merge runs on a worker.
 */
final class PdfBookEngine {

  interface Listener {
    void onLog(String line);
    void onProgress(int done, int total, String status);
    void onFinished(boolean ok, File book);
  }

  private static final long SETTLE_MS = 1500;        // let late resources paint
  private static final long PAGE_TIMEOUT_MS = 45000;
  private static final int MAX_PAGES = 500;          // safety cap

  // A4 at 72 dpi (points); render wider for crisp text then scale to fit.
  private static final int PAGE_W_PT = 595;
  private static final int PAGE_H_PT = 842;
  private static final int RENDER_W_PX = 1080;
  private static final float SCALE = (float) PAGE_W_PT / RENDER_W_PX;

  private final WebView web;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private File workDir;
  private File bookFile;
  private final List<File> htmlFiles = new ArrayList<File>();
  private final List<File> pdfs = new ArrayList<File>();
  private final List<String> titles = new ArrayList<String>();
  private int idx;
  private boolean pageDone;
  private volatile boolean cancelled;

  PdfBookEngine(final Context ctx, final WebView web, final Listener listener) {
    this.web = web;
    this.listener = listener;
    final WebSettings ws = web.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setLoadWithOverviewMode(true);
    ws.setUseWideViewPort(true);
    ws.setAllowFileAccess(true);
    ws.setAllowFileAccessFromFileURLs(true);
    ws.setAllowUniversalAccessFromFileURLs(true);
    ws.setBlockNetworkImage(false);
    // draw() only renders onto a software canvas if the layer is software.
    web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
  }

  void cancel() {
    cancelled = true;
  }

  File getBookFile() {
    return bookFile;
  }

  /** Must be called on the main thread. */
  void start(final File dir) {
    workDir = new File(dir, ".pdf_pages");
    workDir.mkdirs();
    bookFile = new File(dir, "book.pdf");

    scanHtml(dir);
    if (htmlFiles.isEmpty()) {
      listener.onLog("No HTML pages found under " + dir.getAbsolutePath());
      listener.onFinished(false, null);
      return;
    }
    listener.onLog("Found " + htmlFiles.size() + " HTML page(s).");
    idx = 0;
    handler.post(renderNext);
  }

  private void scanHtml(final File root) {
    final File[] children = root.listFiles();
    if (children == null) {
      return;
    }
    Arrays.sort(children, new Comparator<File>() {
      @Override public int compare(final File a, final File b) {
        return a.getName().compareToIgnoreCase(b.getName());
      }
    });
    for (final File f : children) {
      if (cancelled || htmlFiles.size() >= MAX_PAGES) {
        return;
      }
      if (f.isDirectory()) {
        final String n = f.getName();
        if (n.equals("hts-cache") || n.equals(".pdf_pages")) {
          continue;
        }
        scanHtml(f);
      } else {
        final String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".html") || n.endsWith(".htm")) {
          htmlFiles.add(f);
        }
      }
    }
  }

  private final Runnable renderNext = new Runnable() {
    @Override public void run() {
      if (cancelled || idx >= htmlFiles.size()) {
        startMerge();
        return;
      }
      final File html = htmlFiles.get(idx);
      listener.onProgress(idx, htmlFiles.size(),
          "Converting " + (idx + 1) + "/" + htmlFiles.size());
      listener.onLog("[" + (idx + 1) + "] " + html.getName());

      final File outPdf = new File(workDir,
          String.format(Locale.US, "page_%04d.pdf", idx + 1));
      pageDone = false;

      handler.postDelayed(new Runnable() {
        @Override public void run() {
          if (!pageDone) {
            listener.onLog("  timeout, skipping");
            advance(false, null, null);
          }
        }
      }, PAGE_TIMEOUT_MS);

      web.setWebViewClient(new WebViewClient() {
        private boolean settled = false;
        @Override public void onPageFinished(final WebView v, final String url) {
          if (settled) { return; }
          settled = true;
          handler.postDelayed(new Runnable() {
            @Override public void run() {
              final boolean ok = drawToPdf(outPdf);
              advance(ok, outPdf, html);
            }
          }, SETTLE_MS);
        }
      });
      web.loadUrl(Uri.fromFile(html).toString());
    }
  };

  /** Draw the currently loaded page onto an A4 (multi-page) PdfDocument. */
  private boolean drawToPdf(final File outFile) {
    if (pageDone) { return false; }
    FileOutputStream out = null;
    try {
      web.measure(
          View.MeasureSpec.makeMeasureSpec(RENDER_W_PX, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      final int contentH = Math.max(1, web.getMeasuredHeight());
      web.layout(0, 0, RENDER_W_PX, contentH);

      final int pageHeightPx = Math.max(1, (int) (PAGE_H_PT / SCALE));
      final int pages = Math.max(1, (contentH + pageHeightPx - 1) / pageHeightPx);

      final PdfDocument doc = new PdfDocument();
      for (int i = 0; i < pages; i++) {
        final PdfDocument.PageInfo info =
            new PdfDocument.PageInfo.Builder(PAGE_W_PT, PAGE_H_PT, i + 1).create();
        final PdfDocument.Page page = doc.startPage(info);
        final Canvas c = page.getCanvas();
        c.save();
        c.scale(SCALE, SCALE);
        c.translate(0f, (float) (-i * pageHeightPx));
        web.draw(c);
        c.restore();
        doc.finishPage(page);
      }
      out = new FileOutputStream(outFile);
      doc.writeTo(out);
      doc.close();
      return true;
    } catch (final Throwable t) {
      listener.onLog("  pdf error: " + t.getMessage());
      return false;
    } finally {
      if (out != null) {
        try { out.close(); } catch (final Throwable t) { /* ignore */ }
      }
    }
  }

  private void advance(final boolean ok, final File pdf, final File htmlSrc) {
    if (pageDone) { return; }
    pageDone = true;
    if (ok && pdf != null && pdf.length() > 0) {
      pdfs.add(pdf);
      titles.add(titleFor(htmlSrc));
      listener.onLog("  ok (" + (pdf.length() / 1024) + " KB)");
    }
    idx++;
    handler.post(renderNext);
  }

  private void startMerge() {
    if (pdfs.isEmpty()) {
      listener.onLog("[done] no pages produced");
      listener.onFinished(false, null);
      return;
    }
    listener.onProgress(htmlFiles.size(), htmlFiles.size(),
        "Merging " + pdfs.size() + " page(s)…");
    listener.onLog("[merge] building book.pdf with table of contents…");
    new Thread(new Runnable() {
      @Override public void run() {
        boolean ok;
        try {
          ok = BookBuilder.mergeWithToc(pdfs, titles, bookFile);
        } catch (final Throwable t) {
          ok = false;
          final String m = t.getMessage();
          handler.post(new Runnable() {
            @Override public void run() { listener.onLog("[merge] error: " + m); }
          });
        }
        final boolean done = ok;
        handler.post(new Runnable() {
          @Override public void run() {
            cleanup(done);
            listener.onFinished(done, done ? bookFile : null);
          }
        });
      }
    }).start();
  }

  private void cleanup(final boolean ok) {
    if (ok && workDir != null) {
      final File[] tmp = workDir.listFiles();
      if (tmp != null) {
        for (final File f : tmp) { f.delete(); }
      }
      workDir.delete();
    }
  }

  // -- title extraction --------------------------------------------------

  private static final Pattern TITLE_RE =
      Pattern.compile("<title[^>]*>(.*?)</title>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern H1_RE =
      Pattern.compile("<h1[^>]*>(.*?)</h1>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private String titleFor(final File html) {
    if (html == null) { return "Page"; }
    try {
      final byte[] buf = new byte[64 * 1024];
      final int n;
      final FileInputStream in = new FileInputStream(html);
      try {
        n = in.read(buf);
      } finally {
        in.close();
      }
      if (n > 0) {
        final String head = new String(buf, 0, n, "UTF-8");
        String t = firstGroup(TITLE_RE, head);
        if (t == null) { t = firstGroup(H1_RE, head); }
        if (t != null) {
          t = t.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
          if (t.length() > 0) {
            return t.length() > 200 ? t.substring(0, 200) : t;
          }
        }
      }
    } catch (final Throwable t) {
      // fall through to file name
    }
    return html.getName();
  }

  private static String firstGroup(final Pattern p, final String s) {
    final Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
}
