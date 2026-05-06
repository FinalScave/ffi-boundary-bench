package com.ffibb.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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
  private static final String READY_REPORT = "Ready to run Android benchmark.";

  private TextView reportView;
  private Button runButton;
  private Button copyButton;
  private String currentReport = READY_REPORT;
  private boolean running;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(24, 24, 24, 24);
    root.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout toolbar = new LinearLayout(this);
    toolbar.setOrientation(LinearLayout.HORIZONTAL);
    toolbar.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT));

    runButton = new Button(this);
    runButton.setText("Run");
    runButton.setOnClickListener(view -> runBenchmark());
    toolbar.addView(runButton, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT));

    copyButton = new Button(this);
    copyButton.setText("Copy");
    copyButton.setOnClickListener(view -> copyReport());
    LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT);
    copyParams.leftMargin = 12;
    toolbar.addView(copyButton, copyParams);

    reportView = new TextView(this);
    reportView.setTextIsSelectable(true);
    reportView.setTypeface(Typeface.MONOSPACE);
    reportView.setTextSize(12);
    reportView.setText(currentReport);

    ScrollView scrollView = new ScrollView(this);
    scrollView.addView(reportView, new ScrollView.LayoutParams(
        ScrollView.LayoutParams.MATCH_PARENT,
        ScrollView.LayoutParams.WRAP_CONTENT));

    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1.0f);
    scrollParams.topMargin = 12;

    root.addView(toolbar);
    root.addView(scrollView, scrollParams);
    setContentView(root);

    setRunning(false);
  }

  private void runBenchmark() {
    if (running) {
      return;
    }

    updateReport("Running Android benchmark...");
    setRunning(true);

    new Thread(() -> {
      try {
        String report = AndroidBenchmarkRunner.runAll();
        Log.i(TAG, report);
        SavedReport savedReport = saveBenchmarkReport(report);
        String enrichedReport = appendSavedInfo(report, savedReport);
        runOnUiThread(() -> {
          updateReport(enrichedReport);
          setRunning(false);
          Toast.makeText(this, "Benchmark result saved", Toast.LENGTH_SHORT).show();
        });
      } catch (Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        String report = stringWriter.toString();
        Log.e(TAG, report, throwable);
        File errorFile = saveBenchmarkError(report);
        runOnUiThread(() -> {
          String suffix = errorFile == null ? "" : "\nSaved error: " + errorFile.getAbsolutePath() + '\n';
          updateReport(report + suffix);
          setRunning(false);
          Toast.makeText(this, "Benchmark failed; error saved", Toast.LENGTH_SHORT).show();
        });
      }
    }, "ffibb-benchmark").start();
  }

  private void copyReport() {
    if (currentReport.isEmpty()) {
      return;
    }

    copyToClipboard(currentReport);
    Toast.makeText(this, "Benchmark result copied", Toast.LENGTH_SHORT).show();
  }

  private void updateReport(String report) {
    currentReport = report;
    if (reportView != null) {
      reportView.setText(report);
    }
    if (copyButton != null) {
      copyButton.setEnabled(!report.isEmpty());
    }
  }

  private void setRunning(boolean value) {
    running = value;
    if (runButton != null) {
      runButton.setEnabled(!value);
      runButton.setText(value ? "Running" : "Run");
    }
    if (copyButton != null) {
      copyButton.setEnabled(!currentReport.isEmpty());
    }
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

  private static String appendSavedInfo(String report, SavedReport savedReport) {
    return report + "\nSaved latest: " + savedReport.latestFile.getAbsolutePath()
        + "\nSaved copy: " + savedReport.timestampedFile.getAbsolutePath() + '\n';
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
