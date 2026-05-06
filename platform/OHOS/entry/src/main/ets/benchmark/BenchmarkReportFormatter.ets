import { BenchmarkCase } from './BenchmarkCase';
import { BenchmarkRow, PAYLOAD_FAMILY_BINARY_MODEL, PAYLOAD_FAMILY_U32 } from './BenchmarkRow';
import { BenchmarkSink } from './BenchmarkSink';

export class BenchmarkReportFormatter {
  static format(
    cases: Array<BenchmarkCase>,
    modelCounts: Array<number>,
    u32ElementCounts: Array<number>,
    rows: Array<BenchmarkRow>,
  ): string {
    let report: string = '';
    report += 'ffi-binary-bench OHOS benchmark\n';
    report += 'Payload families: BinaryModel, u32 baseline\n';
    report += 'Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes\n';
    report += 'Operations:\n';
    report += '  BinaryModel: Array<BinaryModel> -> ArrayBuffer -> NAPI -> C API decode\n';
    report += '  BinaryModel: C API encode -> ArrayBuffer -> Array<BinaryModel>\n';
    report += '  u32: Uint32Array -> ArrayBuffer -> NAPI -> C API decode\n';
    report += '  u32: C API encode -> ArrayBuffer -> Uint32Array\n';

    report += '\nDatasets\n';
    for (let i = 0; i < cases.length; i++) {
      const c: BenchmarkCase = cases[i];
      const modelCount: number = modelCounts[i];
      const u32Count: number = u32ElementCounts[i];
      report += `  ${BenchmarkReportFormatter.padRight(c.name, 8)} size=${BenchmarkReportFormatter.padRight(BenchmarkReportFormatter.formatSize(c.targetBytes), 10)} iterations=${c.iterations} binary_models=${modelCount} u32_elements=${u32Count}\n`;
    }

    report = BenchmarkReportFormatter.appendRowsForFamily(report, rows, PAYLOAD_FAMILY_BINARY_MODEL, 'BinaryModel');
    report = BenchmarkReportFormatter.appendRowsForFamily(report, rows, PAYLOAD_FAMILY_U32, 'u32 Baseline');

    report += `\nSink: ${BenchmarkSink.get()}\n`;
    return report;
  }

  private static appendRowsForFamily(
    report: string,
    rows: Array<BenchmarkRow>,
    family: string,
    title: string,
  ): string {
    let acc: string = report + '\n' + title + '\n';
    const headerCells: Array<string> = [
      BenchmarkReportFormatter.padRight('Binding', 31),
      BenchmarkReportFormatter.padRight('Operation', 35),
      BenchmarkReportFormatter.padRight('Case', 12),
      BenchmarkReportFormatter.padLeft('Iterations', 12),
      BenchmarkReportFormatter.padLeft('Elements', 12),
      BenchmarkReportFormatter.padLeft('Avg (ms)', 14),
      BenchmarkReportFormatter.padLeft('Min (ms)', 14),
      BenchmarkReportFormatter.padLeft('Max (ms)', 14),
      BenchmarkReportFormatter.padLeft('Bytes', 14),
    ];
    acc += headerCells.join(' ') + '\n';

    for (const row of rows) {
      if (row.payloadFamily !== family) {
        continue;
      }
      const cells: Array<string> = [
        BenchmarkReportFormatter.padRight(row.binding, 31),
        BenchmarkReportFormatter.padRight(row.operation, 35),
        BenchmarkReportFormatter.padRight(row.caseName, 12),
        BenchmarkReportFormatter.padLeft(String(row.iterations), 12),
        BenchmarkReportFormatter.padLeft(String(row.elementCount), 12),
        BenchmarkReportFormatter.padLeft(row.measurement.averageMs.toFixed(3), 14),
        BenchmarkReportFormatter.padLeft(row.measurement.minMs.toFixed(3), 14),
        BenchmarkReportFormatter.padLeft(row.measurement.maxMs.toFixed(3), 14),
        BenchmarkReportFormatter.padLeft(String(row.targetBytes), 14),
      ];
      acc += cells.join(' ') + '\n';
    }
    return acc;
  }

  private static padRight(value: string, width: number): string {
    if (value.length >= width) {
      return value;
    }
    return value + ' '.repeat(width - value.length);
  }

  private static padLeft(value: string, width: number): string {
    if (value.length >= width) {
      return value;
    }
    return ' '.repeat(width - value.length) + value;
  }

  private static formatSize(bytes: number): string {
    const KIB: number = 1024;
    const MIB: number = 1024 * 1024;
    if (bytes >= MIB) {
      return `${(bytes / MIB).toFixed(2)} MiB`;
    }
    if (bytes >= KIB) {
      return `${(bytes / KIB).toFixed(2)} KiB`;
    }
    return `${bytes} B`;
  }
}
