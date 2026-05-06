package com.ffibb.android;

import java.util.List;
import java.util.Locale;

final class BenchmarkReportFormatter {
  private BenchmarkReportFormatter() {
  }

  static String format(
      List<BenchmarkCase> cases,
      List<Integer> modelCounts,
      List<Integer> u32ElementCounts,
      List<BenchmarkRow> rows) {
    StringBuilder report = new StringBuilder();

    report.append("ffi-binary-bench Android benchmark\n");
    report.append("Payload families: BinaryModel, u32 baseline\n");
    report.append("Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes\n");
    report.append("Operations:\n");
    report.append("  BinaryModel: List<BinaryModel> -> compact bytes -> JNI -> C API decode\n");
    report.append("  BinaryModel: C API encode -> JNI -> compact bytes -> List<BinaryModel>\n");
    report.append("  u32: int[] -> compact bytes -> JNI -> C API decode\n");
    report.append("  u32: C API encode -> JNI -> compact bytes -> int[]\n");
    report.append("\nDatasets\n");

    for (int index = 0; index < cases.size(); index++) {
      BenchmarkCase benchmarkCase = cases.get(index);
      report.append(String.format(
          Locale.US,
          "  %-8s size=%-10s iterations=%d binary_models=%d u32_elements=%d\n",
          benchmarkCase.name,
          formatSize(benchmarkCase.targetBytes),
          benchmarkCase.iterations,
          modelCounts.get(index),
          u32ElementCounts.get(index)));
    }

    appendRowsForPayloadFamily(report, rows, "BinaryModel", "BinaryModel");
    appendRowsForPayloadFamily(report, rows, "u32", "u32 Baseline");

    report.append("\nSink: ").append(BenchmarkSink.value()).append('\n');
    return report.toString();
  }

  private static void appendRowsForPayloadFamily(
      StringBuilder report,
      List<BenchmarkRow> rows,
      String payloadFamily,
      String title) {
    report.append("\n").append(title).append("\n");
    report.append(String.format(
        Locale.US,
      "%-31s %-35s %-12s %12s %12s %14s %14s %14s %14s\n",
        "Binding",
        "Operation",
        "Case",
        "Iterations",
        "Elements",
        "Avg (ms)",
        "Min (ms)",
        "Max (ms)",
        "Bytes"));

    for (BenchmarkRow row : rows) {
      if (!row.payloadFamily.equals(payloadFamily)) {
        continue;
      }

      report.append(String.format(
          Locale.US,
          "%-31s %-35s %-12s %12d %12d %14.3f %14.3f %14.3f %14d\n",
          row.binding,
          row.operation,
          row.caseName,
          row.iterations,
          row.elementCount,
          row.measurement.averageMs,
          row.measurement.minMs,
          row.measurement.maxMs,
          row.targetBytes));
    }
  }

  private static String formatSize(int bytes) {
    double kib = 1024.0;
    double mib = 1024.0 * 1024.0;
    if (bytes >= mib) {
      return String.format(Locale.US, "%.2f MiB", bytes / mib);
    }
    if (bytes >= kib) {
      return String.format(Locale.US, "%.2f KiB", bytes / kib);
    }
    return bytes + " B";
  }
}
