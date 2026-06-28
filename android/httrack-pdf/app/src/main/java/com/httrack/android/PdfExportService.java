package com.httrack.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;

/**
 * Foreground service that converts a finished HTTrack mirror into book.pdf in
 * the background. Shows a progress notification while it works and a
 * tap-to-open notification when done, so the user can leave the app.
 *
 * Started by HTTrackActivity.onMakePdf() with the project dir in EXTRA_DIR.
 */
public class PdfExportService extends Service {
  public static final String EXTRA_DIR = "com.httrack.android.pdf.dir";
  public static final String EXTRA_TITLE = "com.httrack.android.pdf.title";

  private static final String CHANNEL_ID = "pdf_book";
  private static final int NOTIF_ID = 0xBADF;

  private NotificationManager nm;
  private WebView web;
  private PdfBookEngine engine;
  private String projectTitle = "";
  private boolean running;

  @Override
  public void onCreate() {
    super.onCreate();
    PDFBoxResourceLoader.init(getApplicationContext());
    nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    createChannel();
  }

  @Override
  public int onStartCommand(final Intent intent, final int flags, final int startId) {
    // Ignore a second request while one is already running.
    if (running) {
      return START_NOT_STICKY;
    }
    final String path = intent != null ? intent.getStringExtra(EXTRA_DIR) : null;
    if (path == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    projectTitle = intent.getStringExtra(EXTRA_TITLE);
    if (projectTitle == null) {
      projectTitle = new File(path).getName();
    }
    running = true;

    startForeground(NOTIF_ID, buildProgress("Starting…", 0, 0, true));

    // WebView must live on the main thread; the service runs there by default.
    web = new WebView(this);
    engine = new PdfBookEngine(getApplicationContext(), web, new PdfBookEngine.Listener() {
      @Override public void onLog(final String line) {
        // Logged to logcat only; the notification carries user-facing status.
        android.util.Log.d("PdfExport", line);
      }
      @Override public void onProgress(final int done, final int total, final String status) {
        nm.notify(NOTIF_ID, buildProgress(status, done, total, total == 0));
      }
      @Override public void onFinished(final boolean ok, final File book) {
        finishUp(ok, book);
      }
    });
    engine.start(new File(path));
    return START_NOT_STICKY;
  }

  private void finishUp(final boolean ok, final File book) {
    running = false;
    // Drop the ongoing notification, then post a final dismissible one.
    stopForeground(true);
    final Notification done = ok && book != null
        ? buildDone(book)
        : buildFailed();
    nm.notify(NOTIF_ID + 1, done);
    if (web != null) {
      web.destroy();
      web = null;
    }
    stopSelf();
  }

  // -- notifications -----------------------------------------------------

  private void createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      final NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
          "PDF book", NotificationManager.IMPORTANCE_LOW);
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    }
  }

  private NotificationCompat.Builder base() {
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOnlyAlertOnce(true)
        .setContentTitle("PDF book: " + projectTitle);
  }

  private Notification buildProgress(final String status, final int done,
                                     final int total, final boolean indeterminate) {
    final NotificationCompat.Builder b = base()
        .setContentText(status)
        .setOngoing(true);
    if (indeterminate) {
      b.setProgress(0, 0, true);
    } else {
      b.setProgress(Math.max(total, 1), done, false);
    }
    return b.build();
  }

  private Notification buildDone(final File book) {
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("PDF book ready: " + projectTitle)
        .setContentText(book.getName() + " (" + (book.length() / 1024) + " KB) — tap to open")
        .setAutoCancel(true)
        .setContentIntent(openIntent(book))
        .build();
  }

  private Notification buildFailed() {
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("PDF book failed: " + projectTitle)
        .setContentText("No pages were converted.")
        .setAutoCancel(true)
        .build();
  }

  private PendingIntent openIntent(final File book) {
    final Uri uri = FileProvider.getUriForFile(this,
        getPackageName() + ".fileprovider", book);
    final Intent view = new Intent(Intent.ACTION_VIEW);
    view.setDataAndType(uri, "application/pdf");
    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_ACTIVITY_NEW_TASK);
    final Intent chooser = Intent.createChooser(view, "Open book.pdf");
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    return PendingIntent.getActivity(this, 0, chooser, flags);
  }

  @Override
  public IBinder onBind(final Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    if (web != null) {
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }
}
