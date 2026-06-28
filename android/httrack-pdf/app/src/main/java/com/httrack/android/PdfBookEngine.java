package com.httrack.android;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
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
 * Renders every saved HTML page of a finished HTTrack mirror to PDF using the
 * device WebView + native print pipeline (no Chrome), then merges them into a
 * single book.pdf with a clickable table of contents (PDFBox). All WebView
 * work happens on the main thread; the merge runs on a worker thread.
 *
 * Drive it from a foreground Service (background conversion) or an Activity.
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

  private final Context ctx;
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
    this.ctx = ctx;
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
            @Override public void run() { printToPdf(outPdf, html); }
          }, SETTLE_MS);
        }
      });
      web.loadUrl(Uri.fromFile(html).toString());
    }
  };

  private void printToPdf(final File outPdf, final File htmlSrc) {
    if (pageDone) { return; }
    try {
      final PrintDocumentAdapter adapter = web.createPrintDocumentAdapter("page");
      final PrintAttributes attrs = new PrintAttributes.Builder()
          .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
          .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
          .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
          .build();
      adapter.onStart();
      adapter.onLayout(null, attrs, new CancellationSignal(),
          new PrintDocumentAdapter.LayoutResultCallback() {
            @Override public void onLayoutFinished(final PrintDocumentInfo info,
                                                   final boolean changed) {
              ParcelFileDescriptor pfd = null;
              try {
                pfd = ParcelFileDescriptor.open(outPdf,
                    ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
                final ParcelFileDescriptor fd = pfd;
                adapter.onWrite(new PageRange[] { PageRange.ALL_PAGES }, fd,
                    new CancellationSignal(),
                    new PrintDocumentAdapter.WriteResultCallback() {
                      @Override public void onWriteFinished(final PageRange[] pages) {
                        try { adapter.onFinish(); } catch (final Throwable t) { /* ignore */ }
                        try { fd.close(); } catch (final Throwable t) { /* ignore */ }
                        advance(true, outPdf, htmlSrc);
                      }
                      @Override public void onWriteFailed(final CharSequence error) {
                        try { fd.close(); } catch (final Throwable t) { /* ignore */ }
                        listener.onLog("  write failed: " + error);
                        advance(false, null, null);
                      }
                    });
              } catch (final Throwable t) {
                try { if (pfd != null) { pfd.close(); } } catch (final Throwable t2) { /* ignore */ }
                listener.onLog("  pdf error: " + t.getMessage());
                advance(false, null, null);
              }
            }
            @Override public void onLayoutFailed(final CharSequence error) {
              listener.onLog("  layout failed: " + error);
              advance(false, null, null);
            }
          }, null);
    } catch (final Throwable t) {
      listener.onLog("  adapter error: " + t.getMessage());
      advance(false, null, null);
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
