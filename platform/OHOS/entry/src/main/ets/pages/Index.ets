import pasteboard from '@ohos.pasteboard';
import promptAction from '@ohos.promptAction';
import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import { OhosBenchmarkRunner } from '../benchmark/OhosBenchmarkRunner';

const REPORT_LATEST_FILE: string = 'ffibb-benchmark-latest.txt';
const REPORT_FILE_PREFIX: string = 'ffibb-benchmark-';
const ERROR_LATEST_FILE: string = 'ffibb-benchmark-error-latest.txt';

class SavedReport {
  latestPath: string;
  timestampedPath: string;

  constructor(latestPath: string, timestampedPath: string) {
    this.latestPath = latestPath;
    this.timestampedPath = timestampedPath;
  }
}

@Entry
@Component
struct Index {
  @State report: string = 'Running OHOS BinaryModel benchmark...';
  @State running: boolean = true;

  aboutToAppear(): void {
    setTimeout(() => {
      this.startBenchmark();
    }, 50);
  }

  private startBenchmark(): void {
    try {
      const text: string = OhosBenchmarkRunner.runAll();
      const saved: SavedReport = this.saveBenchmarkReport(text);
      const enriched: string = appendSavedInfo(text, saved);
      this.report = enriched;
      this.copyToClipboard(enriched);
      promptAction.showToast({ message: 'Benchmark result copied and saved' });
    } catch (e) {
      const message: string = e instanceof Error ? (e.stack ?? e.message) : String(e);
      const errorPath: string | null = this.saveBenchmarkError(message);
      const suffix: string = errorPath === null
        ? ''
        : '\n\nSaved error: ' + errorPath
          + '\n\nPull from device:\n  hdb file recv ' + errorPath + ' ./\n';
      this.report = `Benchmark failed:\n${message}${suffix}`;
      promptAction.showToast({ message: 'Benchmark failed; error saved' });
    } finally {
      this.running = false;
    }
  }

  private saveBenchmarkReport(text: string): SavedReport {
    const dir: string = this.reportDirectory();
    const latestPath: string = dir + '/' + REPORT_LATEST_FILE;
    const timestampedPath: string = dir + '/' + REPORT_FILE_PREFIX + timestamp() + '.txt';
    writeText(latestPath, text);
    writeText(timestampedPath, text);
    return new SavedReport(latestPath, timestampedPath);
  }

  private saveBenchmarkError(text: string): string | null {
    try {
      const errorPath: string = this.reportDirectory() + '/' + ERROR_LATEST_FILE;
      writeText(errorPath, text);
      return errorPath;
    } catch (_) {
      return null;
    }
  }

  private reportDirectory(): string {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    return context.filesDir;
  }

  private copyToClipboard(text: string): void {
    try {
      const data: pasteboard.PasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(data);
    } catch (_) {
    }
  }

  build() {
    Column() {
      Scroll() {
        Text(this.report)
          .fontSize(12)
          .fontFamily('monospace')
          .copyOption(CopyOptions.LocalDevice)
          .padding(12)
          .width('100%')
          .textAlign(TextAlign.Start)
      }
      .layoutWeight(1)
      .width('100%')
    }
    .height('100%')
    .width('100%')
  }
}

function appendSavedInfo(report: string, saved: SavedReport): string {
  return report
    + '\n\nSaved latest: ' + saved.latestPath
    + '\nSaved copy:   ' + saved.timestampedPath
    + '\n\nPull from device:'
    + '\n  hdb file recv ' + saved.latestPath + ' ./'
    + '\n  hdb file recv ' + saved.timestampedPath + ' ./'
    + '\n';
}

function timestamp(): string {
  const d: Date = new Date();
  return d.getFullYear().toString()
    + pad2(d.getMonth() + 1)
    + pad2(d.getDate())
    + '-'
    + pad2(d.getHours())
    + pad2(d.getMinutes())
    + pad2(d.getSeconds());
}

function pad2(n: number): string {
  return n < 10 ? '0' + n.toString() : n.toString();
}

function writeText(path: string, text: string): void {
  const file: fs.File = fs.openSync(
    path,
    fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC,
  );
  try {
    fs.writeSync(file.fd, text);
  } finally {
    fs.closeSync(file);
  }
}
