package com.ffibb.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
  private static final String TAG = "ffibb";
  private static final String REPORT_LATEST_FILE = "ffibb-benchmark-latest.txt";
  private static final String REPORT_FILE_PREFIX = "ffibb-benchmark-";
  private static final String ERROR_LATEST_FILE = "ffibb-benchmark-error-latest.txt";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    TextView textView = new TextView(this);
    textView.setText("Running Android benchmark...");
    textView.setTextIsSelectable(true);
    textView.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));

    ScrollView scrollView = new ScrollView(this);
    scrollView.addView(textView);
    setContentView(scrollView);

    new Thread(() -> {
      try {
        String report = AndroidBenchmarkRunner.runAll();
        Log.i(TAG, report);
        SavedReport savedReport = saveBenchmarkReport(report);
        runOnUiThread(() -> {
          copyToClipboard(report);
          textView.setText(report + "\nSaved latest: " + savedReport.latestFile.getAbsolutePath()
              + "\nSaved copy: " + savedReport.timestampedFile.getAbsolutePath() + '\n');
          Toast.makeText(this, "Benchmark result copied and saved", Toast.LENGTH_SHORT).show();
        });
      } catch (Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        String report = stringWriter.toString();
        Log.e(TAG, report, throwable);
        File errorFile = saveBenchmarkError(report);
        runOnUiThread(() -> {
          String suffix = errorFile == null ? "" : "\nSaved error: " + errorFile.getAbsolutePath() + '\n';
          textView.setText(report + suffix);
          Toast.makeText(this, "Benchmark failed; error saved", Toast.LENGTH_SHORT).show();
        });
      }
    }, "ffibb-benchmark").start();
  }

  private void copyToClipboard(String report) {
    ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboardManager != null) {
      clipboardManager.setPrimaryClip(ClipData.newPlainText("ffi-binary-bench", report));
    }
  }

  private SavedReport saveBenchmarkReport(String report) throws IOException {
    File directory = reportDirectory();
    File latestFile = new File(directory, REPORT_LATEST_FILE);
    File timestampedFile = new File(directory, REPORT_FILE_PREFIX + timestamp() + ".txt");
    writeText(latestFile, report);
    writeText(timestampedFile, report);
    return new SavedReport(latestFile, timestampedFile);
  }

  private File saveBenchmarkError(String report) {
    try {
      File errorFile = new File(reportDirectory(), ERROR_LATEST_FILE);
      writeText(errorFile, report);
      return errorFile;
    } catch (IOException exception) {
      Log.e(TAG, "Failed to save benchmark error report.", exception);
      return null;
    }
  }

  private File reportDirectory() throws IOException {
    File directory = getExternalFilesDir(null);
    if (directory == null) {
      directory = getFilesDir();
    }
    if (!directory.exists() && !directory.mkdirs()) {
      throw new IOException("Failed to create report directory: " + directory.getAbsolutePath());
    }
    return directory;
  }

  private static String timestamp() {
    return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
  }

  private static void writeText(File file, String text) throws IOException {
    try (OutputStreamWriter writer = new OutputStreamWriter(
        new FileOutputStream(file, false),
        StandardCharsets.UTF_8)) {
      writer.write(text);
    }
  }

  private static final class SavedReport {
    final File latestFile;
    final File timestampedFile;

    SavedReport(File latestFile, File timestampedFile) {
      this.latestFile = latestFile;
      this.timestampedFile = timestampedFile;
    }
  }
}
