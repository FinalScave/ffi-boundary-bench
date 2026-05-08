package com.ffibb.jvmwasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BenchmarkSupport {
  static final String PAYLOAD_FAMILY_BINARY_MODEL = "BinaryModel";
  static final String PAYLOAD_FAMILY_U32 = "u32";
  static final int RECORD_HEADER_BYTES = 4 + 4 + 4;

  private BenchmarkSupport() {
  }

  static void writeIntLE(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value & 0xFF);
    bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
    bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
  }

  static int readIntLE(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF) |
        ((bytes[offset + 1] & 0xFF) << 8) |
        ((bytes[offset + 2] & 0xFF) << 16) |
        ((bytes[offset + 3] & 0xFF) << 24);
  }
}

record Arguments(Path wasmPath, Path jsonPath, WasmRuntime runtime) {
  static Arguments parse(String[] args) {
    Path wasmPath = null;
    Path jsonPath = null;
    WasmRuntime runtime = null;

    for (int index = 0; index < args.length; index++) {
      String arg = args[index];
      if ("--wasm".equals(arg)) {
        index += 1;
        if (index >= args.length) {
          throw new IllegalArgumentException("--wasm requires a path.");
        }
        wasmPath = Path.of(args[index]);
      } else if ("--json".equals(arg)) {
        index += 1;
        if (index >= args.length) {
          throw new IllegalArgumentException("--json requires a path.");
        }
        jsonPath = Path.of(args[index]);
      } else if ("--runtime".equals(arg)) {
        index += 1;
        if (index >= args.length) {
          throw new IllegalArgumentException("--runtime requires graalwasm or chicory.");
        }
        runtime = WasmRuntime.parse(args[index]);
      } else {
        throw new IllegalArgumentException("Unknown argument: " + arg);
      }
    }

    if (wasmPath == null) {
      wasmPath = defaultWasmPath();
    }
    if (runtime == null) {
      runtime = defaultRuntime();
    }

    return new Arguments(wasmPath.toAbsolutePath().normalize(), jsonPath, runtime);
  }

  private static Path defaultWasmPath() {
    String propertyValue = System.getProperty("ffibb.wasm");
    if (propertyValue != null && !propertyValue.isBlank()) {
      return Path.of(propertyValue);
    }

    String environmentValue = System.getenv("FFIBB_WASM");
    if (environmentValue != null && !environmentValue.isBlank()) {
      return Path.of(environmentValue);
    }

    Path cwd = Path.of(System.getProperty("user.dir"));
    List<Path> candidates = List.of(
        cwd.resolve("../../build/emscripten-standalone/bin/ffibb_wasm_cabi.wasm"),
        cwd.resolve("../../build/emscripten/bin/ffibb_wasm_cabi.wasm"),
        cwd.resolve("../../cmake-build-emscripten/bin/ffibb_wasm_cabi.wasm"),
        cwd.resolve("build/emscripten-standalone/bin/ffibb_wasm_cabi.wasm"),
        cwd.resolve("build/emscripten/bin/ffibb_wasm_cabi.wasm"),
        cwd.resolve("cmake-build-emscripten/bin/ffibb_wasm_cabi.wasm"));

    for (Path candidate : candidates) {
      if (Files.exists(candidate)) {
        return candidate;
      }
    }

    return candidates.get(0);
  }

  private static WasmRuntime defaultRuntime() {
    String propertyValue = System.getProperty("ffibb.wasm.runtime");
    if (propertyValue != null && !propertyValue.isBlank()) {
      return WasmRuntime.parse(propertyValue);
    }

    String environmentValue = System.getenv("FFIBB_WASM_RUNTIME");
    if (environmentValue != null && !environmentValue.isBlank()) {
      return WasmRuntime.parse(environmentValue);
    }

    return WasmRuntime.GRAALWASM;
  }
}


record BinaryModel(int intValue, float floatValue, String stringValue) {
}


final class BinaryModelPayloadFactory {
  private BinaryModelPayloadFactory() {
  }

  static List<BinaryModel> createModelsForTargetBytes(int targetBytes) {
    if (targetBytes < BenchmarkSupport.RECORD_HEADER_BYTES) {
      throw new IllegalArgumentException("Target payload is too small for BinaryModel.");
    }

    ArrayList<BinaryModel> models = new ArrayList<>();
    int usedBytes = 0;
    int index = 0;

    while (usedBytes < targetBytes) {
      int remaining = targetBytes - usedBytes;
      if (remaining < BenchmarkSupport.RECORD_HEADER_BYTES) {
        if (models.isEmpty()) {
          throw new IllegalStateException("BinaryModel payload generation failed.");
        }

        BinaryModel last = models.remove(models.size() - 1);
        String extended = last.stringValue() + buildAsciiString(index, remaining);
        models.add(new BinaryModel(last.intValue(), last.floatValue(), extended));
        usedBytes += remaining;
        break;
      }

      int maxStringBytes = remaining - BenchmarkSupport.RECORD_HEADER_BYTES;
      int stringBytes = Math.min(maxStringBytes, 12 + (index % 19));
      int tailBytes = remaining - BenchmarkSupport.RECORD_HEADER_BYTES - stringBytes;
      if (tailBytes > 0 && tailBytes < BenchmarkSupport.RECORD_HEADER_BYTES) {
        stringBytes += tailBytes;
      }

      models.add(makeBinaryModel(index, stringBytes));
      usedBytes += BenchmarkSupport.RECORD_HEADER_BYTES + stringBytes;
      index += 1;
    }

    if (BinaryModelWireCodec.encodedSize(models) != targetBytes) {
      throw new IllegalStateException("BinaryModel payload generation did not hit the requested size.");
    }

    return models;
  }

  private static BinaryModel makeBinaryModel(int index, int stringBytes) {
    int intValue = 1000 + (index * 17);
    float floatValue = 0.5f + (index * 0.25f);
    String stringValue = buildAsciiString(index, stringBytes);
    return new BinaryModel(intValue, floatValue, stringValue);
  }

  private static String buildAsciiString(int index, int targetBytes) {
    StringBuilder builder = new StringBuilder("model_").append(index).append('_');
    if (builder.length() > targetBytes) {
      builder.setLength(targetBytes);
      return builder.toString();
    }

    builder.ensureCapacity(targetBytes);
    while (builder.length() < targetBytes) {
      builder.append((char) ('a' + ((index + builder.length()) % 26)));
    }

    return builder.toString();
  }
}


final class U32PayloadCodec {
  private U32PayloadCodec() {
  }

  static int[] createValuesForTargetBytes(int targetBytes) {
    int count = elementCountForBytes(targetBytes);
    int[] values = new int[count];
    fillPattern(values);
    return values;
  }

  static byte[] encode(int[] values) {
    byte[] bytes = new byte[values.length * Integer.BYTES];
    int offset = 0;
    for (int value : values) {
      BenchmarkSupport.writeIntLE(bytes, offset, value);
      offset += Integer.BYTES;
    }
    return bytes;
  }

  static void encodeToMemory(int[] values, WasmMemory memory, int pointer) {
    int offset = pointer;
    for (int value : values) {
      memory.writeU32(offset, value);
      offset += Integer.BYTES;
    }
  }

  static int[] decode(byte[] bytes) {
    int[] values = new int[elementCountForBytes(bytes.length)];
    int offset = 0;
    for (int index = 0; index < values.length; index++) {
      values[index] = BenchmarkSupport.readIntLE(bytes, offset);
      offset += Integer.BYTES;
    }
    return values;
  }

  static int[] decode(WasmMemory memory, int pointer, int size) {
    int[] values = new int[elementCountForBytes(size)];
    int offset = pointer;
    for (int index = 0; index < values.length; index++) {
      values[index] = memory.readU32(offset);
      offset += Integer.BYTES;
    }
    return values;
  }

  static long fingerprint(int[] values) {
    if (values.length == 0) {
      return 0L;
    }

    int middle = values.length / 2;
    return (values.length
        + Integer.toUnsignedLong(values[0])
        + Integer.toUnsignedLong(values[middle])
        + Integer.toUnsignedLong(values[values.length - 1])) & 0xFFFFFFFFL;
  }

  private static int elementCountForBytes(int byteCount) {
    if (byteCount % Integer.BYTES != 0) {
      throw new IllegalArgumentException("Byte count must align with uint32 elements.");
    }
    return byteCount / Integer.BYTES;
  }

  private static void fillPattern(int[] values) {
    for (int index = 0; index < values.length; index++) {
      long lhs = (index * 2654435761L) & 0xFFFFFFFFL;
      long rhs = (0x9E3779B9L + index * 17L) & 0xFFFFFFFFL;
      values[index] = (int) (lhs ^ rhs);
    }
  }
}


final class BinaryModelWireCodec {
  private BinaryModelWireCodec() {
  }

  static byte[] encode(List<BinaryModel> models) {
    byte[] bytes = new byte[encodedSize(models)];
    int offset = 0;

    for (BinaryModel model : models) {
      BenchmarkSupport.writeIntLE(bytes, offset, model.intValue());
      offset += 4;
      BenchmarkSupport.writeIntLE(bytes, offset, Float.floatToRawIntBits(model.floatValue()));
      offset += 4;

      byte[] stringBytes = model.stringValue().getBytes(StandardCharsets.UTF_8);
      BenchmarkSupport.writeIntLE(bytes, offset, stringBytes.length);
      offset += 4;

      System.arraycopy(stringBytes, 0, bytes, offset, stringBytes.length);
      offset += stringBytes.length;
    }

    return bytes;
  }

  static void encodeToMemory(List<BinaryModel> models, WasmMemory memory, int pointer) {
    int offset = pointer;
    for (BinaryModel model : models) {
      memory.writeU32(offset, model.intValue());
      offset += 4;
      memory.writeU32(offset, Float.floatToRawIntBits(model.floatValue()));
      offset += 4;

      byte[] stringBytes = model.stringValue().getBytes(StandardCharsets.UTF_8);
      memory.writeU32(offset, stringBytes.length);
      offset += 4;

      memory.writeBytes(offset, stringBytes);
      offset += stringBytes.length;
    }
  }

  static List<BinaryModel> decode(byte[] bytes) {
    ArrayList<BinaryModel> models = new ArrayList<>();
    int offset = 0;

    while (offset < bytes.length) {
      if (bytes.length - offset < BenchmarkSupport.RECORD_HEADER_BYTES) {
        throw new IllegalArgumentException("Unexpected end of compact byte payload.");
      }

      int intValue = BenchmarkSupport.readIntLE(bytes, offset);
      offset += 4;

      float floatValue = Float.intBitsToFloat(BenchmarkSupport.readIntLE(bytes, offset));
      offset += 4;

      int stringSize = BenchmarkSupport.readIntLE(bytes, offset);
      offset += 4;

      if (stringSize < 0 || bytes.length - offset < stringSize) {
        throw new IllegalArgumentException("String size exceeds compact payload size.");
      }

      String stringValue = new String(bytes, offset, stringSize, StandardCharsets.UTF_8);
      offset += stringSize;
      models.add(new BinaryModel(intValue, floatValue, stringValue));
    }

    return models;
  }

  static List<BinaryModel> decode(WasmMemory memory, int pointer, int size) {
    ArrayList<BinaryModel> models = new ArrayList<>();
    int offset = 0;

    while (offset < size) {
      if (size - offset < BenchmarkSupport.RECORD_HEADER_BYTES) {
        throw new IllegalArgumentException("Unexpected end of compact byte payload.");
      }

      int intValue = memory.readU32(pointer + offset);
      offset += 4;

      float floatValue = Float.intBitsToFloat(memory.readU32(pointer + offset));
      offset += 4;

      int stringSize = memory.readU32(pointer + offset);
      offset += 4;

      if (stringSize < 0 || size - offset < stringSize) {
        throw new IllegalArgumentException("String size exceeds compact payload size.");
      }

      byte[] stringBytes = memory.readBytes(pointer + offset, stringSize);
      offset += stringSize;
      models.add(new BinaryModel(intValue, floatValue, new String(stringBytes, StandardCharsets.UTF_8)));
    }

    return models;
  }

  static int encodedSize(List<BinaryModel> models) {
    int totalBytes = 0;
    for (BinaryModel model : models) {
      totalBytes += BenchmarkSupport.RECORD_HEADER_BYTES + model.stringValue().getBytes(StandardCharsets.UTF_8).length;
    }
    return totalBytes;
  }

  static long fingerprint(List<BinaryModel> models) {
    if (models.isEmpty()) {
      return 0L;
    }

    int middle = models.size() / 2;
    BinaryModel[] samples = new BinaryModel[] {
        models.get(0),
        models.get(middle),
        models.get(models.size() - 1)
    };

    long fingerprint = models.size();
    for (BinaryModel model : samples) {
      fingerprint = (fingerprint * 1315423911L) ^ (model.intValue() & 0xFFFFFFFFL);
      fingerprint = (fingerprint * 1315423911L) ^ (Float.floatToRawIntBits(model.floatValue()) & 0xFFFFFFFFL);

      byte[] stringBytes = model.stringValue().getBytes(StandardCharsets.UTF_8);
      fingerprint = (fingerprint * 1315423911L) ^ stringBytes.length;
      for (byte value : stringBytes) {
        fingerprint = (fingerprint * 1099511628211L) ^ (value & 0xFFL);
      }
    }

    return fingerprint;
  }
}


final class BenchmarkTimer {
  private BenchmarkTimer() {
  }

  static BenchmarkMeasurement measure(int iterations, int warmupRounds, BenchmarkTask task) {
    for (int index = 0; index < warmupRounds; index++) {
      BenchmarkMain.consume(task.run());
    }

    double totalMs = 0.0;
    double minMs = Double.MAX_VALUE;
    double maxMs = 0.0;

    for (int index = 0; index < iterations; index++) {
      long begin = System.nanoTime();
      long value = task.run();
      long end = System.nanoTime();

      BenchmarkMain.consume(value);
      double elapsedMs = (end - begin) / 1_000_000.0;
      totalMs += elapsedMs;
      minMs = Math.min(minMs, elapsedMs);
      maxMs = Math.max(maxMs, elapsedMs);
    }

    return new BenchmarkMeasurement(totalMs / iterations, minMs, maxMs);
  }
}


@FunctionalInterface
interface BenchmarkTask {
  long run();
}


record BenchmarkCase(String name, int targetBytes, int iterations) {
}


record BenchmarkMeasurement(double averageMs, double minMs, double maxMs) {
}


record BenchmarkRow(
    String payloadFamily,
    String binding,
    String operation,
    BenchmarkCase benchmarkCase,
    int elementCount,
    BenchmarkMeasurement measurement) {
}


final class BenchmarkReportFormatter {
  private BenchmarkReportFormatter() {
  }

  static String format(
      Path wasmPath,
      WasmRuntime runtime,
      List<BenchmarkCase> cases,
      List<Integer> modelCounts,
      List<Integer> u32ElementCounts,
      List<BenchmarkRow> rows,
      long sink) {
    StringBuilder report = new StringBuilder();

    report.append("ffi-binary-bench JVM Wasm benchmark\n");
    report.append("Payload families: BinaryModel, u32 baseline\n");
    report.append("Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes\n");
    report.append("Module: ").append(wasmPath).append('\n');
    report.append("JVM: ").append(System.getProperty("java.version")).append('\n');
    report.append("Wasm runtime: ").append(runtime.displayName()).append('\n');
    report.append("Operations:\n");
    report.append("  BinaryModel: List<BinaryModel> -> compact bytes -> wasm C API decode\n");
    report.append("  BinaryModel: wasm C API encode -> compact bytes -> List<BinaryModel>\n");
    report.append("  u32: int[] -> compact bytes -> wasm C API decode\n");
    report.append("  u32: wasm C API encode -> compact bytes -> int[]\n");
    report.append("\nDatasets\n");

    for (int index = 0; index < cases.size(); index++) {
      BenchmarkCase benchmarkCase = cases.get(index);
      report.append(String.format(
          Locale.US,
          "  %-8s size=%-10s iterations=%d binary_models=%d u32_elements=%d\n",
          benchmarkCase.name(),
          formatSize(benchmarkCase.targetBytes()),
          benchmarkCase.iterations(),
          modelCounts.get(index),
          u32ElementCounts.get(index)));
    }

    appendRowsForPayloadFamily(report, rows, BenchmarkSupport.PAYLOAD_FAMILY_BINARY_MODEL, "BinaryModel");
    appendRowsForPayloadFamily(report, rows, BenchmarkSupport.PAYLOAD_FAMILY_U32, "u32 Baseline");

    report.append("\nSink: ").append(sink).append('\n');
    return report.toString();
  }

  static void writeJsonResults(Path path, List<BenchmarkRow> rows) throws IOException {
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"rows\": [\n");

    for (int index = 0; index < rows.size(); index++) {
      BenchmarkRow row = rows.get(index);
      json.append("    {\n");
      json.append("      \"payload_family\": \"").append(escapeJson(row.payloadFamily())).append("\",\n");
      json.append("      \"binding\": \"").append(escapeJson(row.binding())).append("\",\n");
      json.append("      \"operation\": \"").append(escapeJson(row.operation())).append("\",\n");
      json.append("      \"case_name\": \"").append(escapeJson(row.benchmarkCase().name())).append("\",\n");
      json.append("      \"payload_bytes\": ").append(row.benchmarkCase().targetBytes()).append(",\n");
      json.append("      \"element_count\": ").append(row.elementCount()).append(",\n");
      json.append("      \"iterations\": ").append(row.benchmarkCase().iterations()).append(",\n");
      json.append(String.format(Locale.US, "      \"average_ms\": %.6f,\n", row.measurement().averageMs()));
      json.append(String.format(Locale.US, "      \"min_ms\": %.6f,\n", row.measurement().minMs()));
      json.append(String.format(Locale.US, "      \"max_ms\": %.6f\n", row.measurement().maxMs()));
      json.append("    }");
      if (index + 1 != rows.size()) {
        json.append(',');
      }
      json.append('\n');
    }

    json.append("  ]\n");
    json.append("}\n");
    Files.writeString(path, json.toString());
  }

  private static void appendRowsForPayloadFamily(
      StringBuilder report,
      List<BenchmarkRow> rows,
      String payloadFamily,
      String title) {
    report.append("\n").append(title).append("\n");
    report.append(String.format(
        Locale.US,
        "%-16s %-35s %-12s %12s %12s %14s %14s %14s %14s\n",
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
      if (!row.payloadFamily().equals(payloadFamily)) {
        continue;
      }

      report.append(String.format(
          Locale.US,
          "%-16s %-35s %-12s %12d %12d %14.3f %14.3f %14.3f %14d\n",
          row.binding(),
          row.operation(),
          row.benchmarkCase().name(),
          row.benchmarkCase().iterations(),
          row.elementCount(),
          row.measurement().averageMs(),
          row.measurement().minMs(),
          row.measurement().maxMs(),
          row.benchmarkCase().targetBytes()));
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

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}

