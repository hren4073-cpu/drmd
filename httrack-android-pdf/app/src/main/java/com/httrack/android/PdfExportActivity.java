package com.httrack.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

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
 * Renders every saved HTML page of a finished HTTrack mirror to PDF (using the
 * device's own WebView + native print pipeline, no Chrome) and merges them into
 * a single {@code book.pdf} with a clickable table of contents (PDFBox).
 *
 * Started by HTTrackActivity.onMakePdf() with the project directory in
 * {@link #EXTRA_DIR}.
 */
public class PdfExportActivity extends Activity {
  public static final String EXTRA_DIR = "com.httrack.android.pdf.dir";
  public static final String EXTRA_TITLE = "com.httrack.android.pdf.title";

  private static final String TAG = "PdfExport";
  private static final long SETTLE_MS = 1500;     // let late resources paint
  private static final long PAGE_TIMEOUT_MS = 45000;
  private static final int MAX_PAGES = 500;       // safety cap for huge mirrors

  private final Handler handler = new Handler(Looper.getMainLooper());

  private WebView web;
  private TextView statusView;
  private TextView logView;
  private ScrollView logScroll;
  private Button openBtn;

  private File dir;          // mirror root
  private File workDir;      // temp per-page PDFs
  private File bookFile;     // final book.pdf

  private List<File> htmlFiles = new ArrayList<File>();
  private List<File> pdfs = new ArrayList<File>();
  private List<String> titles = new ArrayList<String>();
  private int idx;
  private boolean pageDone;
  private volatile boolean cancelled;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    PDFBoxResourceLoader.init(getApplicationContext());
    setContentView(R.layout.activity_pdf_export);

    web = (WebView) findViewById(R.id.pdfWeb);
    statusView = (TextView) findViewById(R.id.pdfStatus);
    logView = (TextView) findViewById(R.id.pdfLog);
    logScroll = (ScrollView) findViewById(R.id.pdfLogScroll);
    openBtn = (Button) findViewById(R.id.pdfOpen);
    final Button cancelBtn = (Button) findViewById(R.id.pdfCancel);

    final WebSettings ws = web.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setLoadWithOverviewMode(true);
    ws.setUseWideViewPort(true);
    ws.setAllowFileAccess(true);
    ws.setAllowFileAccessFromFileURLs(true);
    ws.setAllowUniversalAccessFromFileURLs(true);
    ws.setBlockNetworkImage(false);

    cancelBtn.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(final View v) {
        cancelled = true;
        v.setEnabled(false);
        status("Cancelling…");
      }
    });
    openBtn.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(final View v) { openBook(); }
    });

    final String path = getIntent().getStringExtra(EXTRA_DIR);
    if (path == null) {
      status("No folder supplied.");
      return;
    }
    dir = new File(path);
    final String title = getIntent().getStringExtra(EXTRA_TITLE);
    if (title != null) {
      ((TextView) findViewById(R.id.pdfTitle)).setText("PDF book: " + title);
    }
    workDir = new File(dir, ".pdf_pages");
    workDir.mkdirs();
    bookFile = new File(dir, "book.pdf");

    scanHtml(dir);
    if (htmlFiles.isEmpty()) {
      status("No HTML pages found in this project.");
      log("Nothing to convert under " + dir.getAbsolutePath());
      return;
    }
    log("Found " + htmlFiles.size() + " HTML page(s).");
    status("Converting 0/" + htmlFiles.size() + "…");
    idx = 0;
    handler.post(renderNext);
  }

  /** Collect *.html / *.htm under root, skipping HTTrack's own cache. */
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
      if (cancelled) { startMerge(); return; }
      if (idx >= htmlFiles.size()) { startMerge(); return; }

      final File html = htmlFiles.get(idx);
      status("Converting " + (idx + 1) + "/" + htmlFiles.size() + "…");
      log("[" + (idx + 1) + "] " + html.getName());

      final File outPdf = new File(workDir,
          String.format(Locale.US, "page_%04d.pdf", idx + 1));
      pageDone = false;

      // Watchdog: if a page hangs, move on.
      handler.postDelayed(new Runnable() {
        @Override public void run() {
          if (!pageDone) {
            log("  timeout, skipping");
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
                        log("  write failed: " + error);
                        advance(false, null, null);
                      }
                    });
              } catch (final Throwable t) {
                try { if (pfd != null) { pfd.close(); } } catch (final Throwable t2) { /* ignore */ }
                log("  pdf error: " + t.getMessage());
                advance(false, null, null);
              }
            }
            @Override public void onLayoutFailed(final CharSequence error) {
              log("  layout failed: " + error);
              advance(false, null, null);
            }
          }, null);
    } catch (final Throwable t) {
      Log.w(TAG, "print error", t);
      log("  adapter error: " + t.getMessage());
      advance(false, null, null);
    }
  }

  /** Mark current page complete (once) and queue the next one. */
  private void advance(final boolean ok, final File pdf, final File htmlSrc) {
    if (pageDone) { return; }
    pageDone = true;
    if (ok && pdf != null && pdf.length() > 0) {
      pdfs.add(pdf);
      titles.add(titleFor(htmlSrc));
      log("  ok (" + (pdf.length() / 1024) + " KB)");
    }
    idx++;
    handler.post(renderNext);
  }

  private void startMerge() {
    if (pdfs.isEmpty()) {
      status("Nothing converted.");
      log("[done] no pages produced");
      return;
    }
    status("Merging " + pdfs.size() + " page(s) into book…");
    log("[merge] building book.pdf with table of contents…");
    new Thread(new Runnable() {
      @Override public void run() {
        boolean ok;
        try {
          ok = BookBuilder.mergeWithToc(pdfs, titles, bookFile);
        } catch (final Throwable t) {
          ok = false;
          final String m = t.getMessage();
          runOnUiThread(new Runnable() {
            @Override public void run() { log("[merge] error: " + m); }
          });
        }
        final boolean done = ok;
        runOnUiThread(new Runnable() {
          @Override public void run() { finishUp(done); }
        });
      }
    }).start();
  }

  private void finishUp(final boolean ok) {
    // Clean up the per-page temporaries on success.
    if (ok && workDir != null) {
      final File[] tmp = workDir.listFiles();
      if (tmp != null) {
        for (final File f : tmp) { f.delete(); }
      }
      workDir.delete();
    }
    if (ok) {
      openBtn.setEnabled(true);
      status("Done — book.pdf ready (" + (bookFile.length() / 1024) + " KB).");
      log("[done] " + bookFile.getAbsolutePath());
    } else {
      status("Failed.");
      log("[done] could not build the book");
    }
  }

  private void openBook() {
    if (bookFile == null || !bookFile.exists()) { return; }
    try {
      final Uri uri = FileProvider.getUriForFile(this,
          getPackageName() + ".fileprovider", bookFile);
      final Intent view = new Intent(Intent.ACTION_VIEW);
      view.setDataAndType(uri, "application/pdf");
      view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(view, "Open book.pdf"));
    } catch (final ActivityNotFoundException e) {
      Toast.makeText(this, "No PDF viewer installed.", Toast.LENGTH_LONG).show();
    } catch (final Throwable t) {
      Toast.makeText(this, "Cannot open: " + t.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  /** Bookmark title: <title>, else first <h1>, else the file name. */
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
      int n;
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

  private void status(final String s) { statusView.setText(s); }

  private void log(final String line) {
    logView.append(line + "\n");
    logScroll.post(new Runnable() {
      @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
    });
  }

  @Override
  protected void onDestroy() {
    cancelled = true;
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
