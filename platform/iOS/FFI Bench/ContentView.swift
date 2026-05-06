//
//  ContentView.swift
//  FFI Bench
//
//  Created by Scave on 2026/5/6.
//

import SwiftUI
import UIKit
import FFIBBKit

private struct SavedReport {
  let latest: URL
  let timestamped: URL
}

struct ContentView: View {
  @State private var report = "Ready to run iOS BinaryModel benchmark."
  @State private var isRunning = false

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack {
        Button(action: runBenchmark) {
          Label(isRunning ? "Running" : "Run", systemImage: "play.fill")
        }
        .disabled(isRunning)

        Button(action: copyReport) {
          Label("Copy", systemImage: "doc.on.doc")
        }
        .disabled(report.isEmpty)

        Spacer()
      }

      TextEditor(text: $report)
        .font(.system(.body, design: .monospaced))
        .textSelection(.enabled)
        .frame(minHeight: 480)
    }
    .padding()
  }

  private func runBenchmark() {
    isRunning = true
    report = "Running iOS BinaryModel benchmark..."

    Task.detached {
      do {
        let result = try AppleBenchmarkRunner.runAll()
        let saved = trySaveReport(text: result)
        let enriched = appendSavedInfo(report: result, saved: saved)
        await MainActor.run {
          report = enriched
          UIPasteboard.general.string = enriched
          isRunning = false
        }
      } catch {
        let message = String(describing: error)
        let savedErrorPath = trySaveError(text: message)
        let suffix: String
        if let url = savedErrorPath {
          suffix = "\n\nSaved error: \(url.path)\n"
        } else {
          suffix = ""
        }
        await MainActor.run {
          report = "Benchmark failed:\n\(message)\(suffix)"
          isRunning = false
        }
      }
    }
  }

  private func copyReport() {
    UIPasteboard.general.string = report
  }
}

private func documentsDirectory() -> URL? {
  FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
}

private func trySaveReport(text: String) -> SavedReport? {
  guard let dir = documentsDirectory() else {
    return nil
  }
  let latest = dir.appendingPathComponent("ffibb-benchmark-latest.txt")
  let timestamped = dir.appendingPathComponent("ffibb-benchmark-\(timestampSuffix()).txt")
  do {
    try text.write(to: latest, atomically: true, encoding: .utf8)
    try text.write(to: timestamped, atomically: true, encoding: .utf8)
    return SavedReport(latest: latest, timestamped: timestamped)
  } catch {
    return nil
  }
}

private func trySaveError(text: String) -> URL? {
  guard let dir = documentsDirectory() else {
    return nil
  }
  let url = dir.appendingPathComponent("ffibb-benchmark-error-latest.txt")
  do {
    try text.write(to: url, atomically: true, encoding: .utf8)
    return url
  } catch {
    return nil
  }
}

private func appendSavedInfo(report: String, saved: SavedReport?) -> String {
  guard let saved else {
    return report
  }
  let bundleId = Bundle.main.bundleIdentifier ?? "<bundle-id>"
  return report
    + "\n\nSaved latest: \(saved.latest.path)"
    + "\nSaved copy:   \(saved.timestamped.path)"
    + "\n\nPull from booted simulator:"
    + "\n  cp \"$(xcrun simctl get_app_container booted \(bundleId) data)/Documents/ffibb-benchmark-latest.txt\" ./"
    + "\n\nPull from device:"
    + "\n  Xcode -> Window -> Devices and Simulators -> Installed Apps ->"
    + "\n  \(bundleId) -> ... -> Download Container, then dig into Documents/."
    + "\n"
}

private func timestampSuffix() -> String {
  let formatter = DateFormatter()
  formatter.dateFormat = "yyyyMMdd-HHmmss"
  formatter.locale = Locale(identifier: "en_US_POSIX")
  return formatter.string(from: Date())
}

#Preview {
  ContentView()
}
