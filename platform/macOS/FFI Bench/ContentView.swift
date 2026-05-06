//
//  ContentView.swift
//  FFI Bench
//
//  Created by Scave on 2026/5/6.
//

import SwiftUI
import FFIBBKit
#if os(macOS)
import AppKit
#endif

struct ContentView: View {
    @State private var report = "Ready to run macOS BinaryModel benchmark."
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
                .frame(minHeight: 520)
        }
        .padding()
        .frame(minWidth: 920, minHeight: 640)
    }

    private func runBenchmark() {
        isRunning = true
        report = "Running macOS BinaryModel benchmark..."

        Task.detached {
            do {
                let result = try AppleBenchmarkRunner.runAll()
                await MainActor.run {
                    report = result
                    isRunning = false
                }
            } catch {
                await MainActor.run {
                    report = String(describing: error)
                    isRunning = false
                }
            }
        }
    }

    private func copyReport() {
        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(report, forType: .string)
        #endif
    }
}

#Preview {
    ContentView()
}
