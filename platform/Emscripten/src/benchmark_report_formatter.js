const { formatSize } = require("./benchmark_case");

function padLeft(value, width) {
  return String(value).padStart(width, " ");
}

function padRight(value, width) {
  return String(value).padEnd(width, " ");
}

function fixed(value) {
  return value.toFixed(3);
}

function formatRows(rows, title) {
  const lines = [];
  lines.push("");
  lines.push(title);
  lines.push(
    `${padRight("Binding", 16)}${padRight("Operation", 32)}${padRight("Case", 12)}` +
      `${padLeft("Iterations", 12)}${padLeft("Elements", 12)}${padLeft("Avg (ms)", 14)}` +
      `${padLeft("Min (ms)", 14)}${padLeft("Max (ms)", 14)}${padLeft("Bytes", 14)}`,
  );

  for (const row of rows) {
    lines.push(
      `${padRight(row.binding, 16)}${padRight(row.operation, 32)}${padRight(row.case.name, 12)}` +
        `${padLeft(row.case.iterations, 12)}${padLeft(row.elementCount, 12)}` +
        `${padLeft(fixed(row.measurement.averageMs), 14)}${padLeft(fixed(row.measurement.minMs), 14)}` +
        `${padLeft(fixed(row.measurement.maxMs), 14)}${padLeft(row.case.targetBytes, 14)}`,
    );
  }

  return lines.join("\n");
}

function formatReport({ modulePath, cases, modelCounts, u32ElementCounts, rows, sink }) {
  const lines = [];
  lines.push("ffi-binary-bench Emscripten Node.js benchmark");
  lines.push("Payload families: BinaryModel, u32 baseline");
  lines.push("Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes");
  lines.push(`Module: ${modulePath}`);
  lines.push(`Node.js: ${process.version}`);
  lines.push("Operations:");
  lines.push("  BinaryModel: JS objects -> compact bytes -> wasm C API decode");
  lines.push("  BinaryModel: wasm C API encode -> compact bytes -> JS objects");
  lines.push("  u32: Uint32Array -> compact bytes -> wasm C API decode");
  lines.push("  u32: wasm C API encode -> compact bytes -> Uint32Array/view");
  lines.push("");
  lines.push("Datasets");

  for (let index = 0; index < cases.length; index += 1) {
    const benchmarkCase = cases[index];
    lines.push(
      `  ${padRight(benchmarkCase.name, 8)} size=${padRight(formatSize(benchmarkCase.targetBytes), 10)}` +
        ` iterations=${benchmarkCase.iterations}` +
        ` binary_models=${modelCounts[index]}` +
        ` u32_elements=${u32ElementCounts[index]}`,
    );
  }

  lines.push(formatRows(rows.filter((row) => row.payloadFamily === "BinaryModel"), "BinaryModel"));
  lines.push(formatRows(rows.filter((row) => row.payloadFamily === "u32"), "u32 Baseline"));
  lines.push("");
  lines.push(`Sink: ${sink}`);
  return `${lines.join("\n")}\n`;
}

function jsonRows(rows) {
  return {
    rows: rows.map((row) => ({
      payload_family: row.payloadFamily,
      binding: row.binding,
      operation: row.operation,
      case_name: row.case.name,
      payload_bytes: row.case.targetBytes,
      element_count: row.elementCount,
      iterations: row.case.iterations,
      average_ms: row.measurement.averageMs,
      min_ms: row.measurement.minMs,
      max_ms: row.measurement.maxMs,
    })),
  };
}

module.exports = {
  formatReport,
  jsonRows,
};
