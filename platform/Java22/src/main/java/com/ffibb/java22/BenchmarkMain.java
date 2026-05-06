package com.ffibb.java22;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BenchmarkMain {
  private static final String PAYLOAD_FAMILY_BINARY_MODEL = "BinaryModel";
  private static final String PAYLOAD_FAMILY_U32 = "u32";
  private static final String BINDING_BYTE_ARRAY = "byte_array";
  private static final String BINDING_NATIVE_MEMORY = "native_memory";
  private static final String OPERATION_JAVA_ENCODE_TO_C_API_DECODE = "java_encode_to_c_api_decode";
  private static final String OPERATION_C_API_ENCODE_TO_JAVA_DECODE = "c_api_encode_to_java_decode";
  private static final BenchmarkCase[] CASES = new BenchmarkCase[] {
      new BenchmarkCase("10 KiB", 10 * 1024, 5000),
      new BenchmarkCase("200 KiB", 200 * 1024, 1000),
      new BenchmarkCase("1 MiB", 1024 * 1024, 200)
  };
  private static final int RECORD_HEADER_BYTES = 4 + 4 + 4;
  private static final int WARMUP_ROUNDS = 10;
  private static volatile long sink;

  private BenchmarkMain() {
  }

  public static void main(String[] args) throws Throwable {
    Arguments arguments = Arguments.parse(args);
    FfmBindings ffmBindings = FfmBindings.load(arguments.libraryPath());

    ArrayList<BenchmarkRow> rows = new ArrayList<>();
    ArrayList<Integer> modelCounts = new ArrayList<>();
    ArrayList<Integer> u32ElementCounts = new ArrayList<>();
    sink = 0L;

    for (BenchmarkCase benchmarkCase : CASES) {
      List<BinaryModel> source = BinaryModelPayloadFactory.createModelsForTargetBytes(benchmarkCase.targetBytes());
      int[] u32Source = U32PayloadCodec.createValuesForTargetBytes(benchmarkCase.targetBytes());
      modelCounts.add(source.size());
      u32ElementCounts.add(u32Source.length);

      rows.add(runJavaToNativeDecodeByteArray(ffmBindings, benchmarkCase, source));
      rows.add(runNativeToJavaDecodeByteArray(ffmBindings, benchmarkCase));
      rows.add(runJavaToNativeDecodeNativeMemory(ffmBindings, benchmarkCase, source));
      rows.add(runNativeToJavaDecodeNativeMemory(ffmBindings, benchmarkCase));
      rows.add(runJavaToNativeDecodeU32ByteArray(ffmBindings, benchmarkCase, u32Source));
      rows.add(runNativeToJavaDecodeU32ByteArray(ffmBindings, benchmarkCase, u32Source));
      rows.add(runJavaToNativeDecodeU32NativeMemory(ffmBindings, benchmarkCase, u32Source));
      rows.add(runNativeToJavaDecodeU32NativeMemory(ffmBindings, benchmarkCase, u32Source));
    }

    String report = BenchmarkReportFormatter.format(arguments.libraryPath(), List.of(CASES), modelCounts, u32ElementCounts, rows);
    System.out.print(report);

    if (arguments.jsonPath() != null) {
      writeJsonResults(arguments.jsonPath(), rows);
      System.out.println();
      System.out.println("JSON: " + arguments.jsonPath());
    }
  }

  private static BenchmarkRow runJavaToNativeDecodeByteArray(
      FfmBindings ffmBindings,
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) throws Throwable {
    long expectedFingerprint = BinaryModelWireCodec.fingerprint(source);
    long expectedCount = source.size();

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBytes = arena.allocate(benchmarkCase.targetBytes());
      FfmBindings.ConsumeScratch scratch = ffmBindings.newConsumeScratch(arena);

      byte[] warmupBytes = BinaryModelWireCodec.encode(source);
      copyByteArrayToNative(warmupBytes, nativeBytes);
      FfmBindings.DecodeResult warmupResult =
          ffmBindings.processBinaryModelBytes(nativeBytes, warmupBytes.length, scratch);
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            byte[] bytes = BinaryModelWireCodec.encode(source);
            copyByteArrayToNative(bytes, nativeBytes);
            FfmBindings.DecodeResult result =
                ffmBindings.processBinaryModelBytes(nativeBytes, bytes.length, scratch);
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_BINARY_MODEL,
          BINDING_BYTE_ARRAY,
          OPERATION_JAVA_ENCODE_TO_C_API_DECODE,
          benchmarkCase,
          source.size(),
          measurement);
    }
  }

  private static BenchmarkRow runNativeToJavaDecodeByteArray(FfmBindings ffmBindings, BenchmarkCase benchmarkCase)
      throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      FfmBindings.EncodeScratch scratch = ffmBindings.newEncodeScratch(arena);

      FfmBindings.EncodedBytes warmupBytes =
          ffmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes(), scratch);
      int modelCount;
      try {
        byte[] bytes = copyNativeToByteArray(warmupBytes.data(), warmupBytes.size());
        List<BinaryModel> models = BinaryModelWireCodec.decode(bytes);
        verifyEncodedModels(warmupBytes, models);
        modelCount = models.size();
        consume(BinaryModelWireCodec.fingerprint(models));
      } finally {
        ffmBindings.freeBytes(warmupBytes);
      }

      int[] latestModelCount = new int[] {modelCount};
      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            FfmBindings.EncodedBytes encodedBytes =
                ffmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes(), scratch);
            try {
              byte[] bytes = copyNativeToByteArray(encodedBytes.data(), encodedBytes.size());
              List<BinaryModel> models = BinaryModelWireCodec.decode(bytes);
              verifyEncodedModels(encodedBytes, models);
              latestModelCount[0] = models.size();
              return BinaryModelWireCodec.fingerprint(models);
            } finally {
              ffmBindings.freeBytes(encodedBytes);
            }
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_BINARY_MODEL,
          BINDING_BYTE_ARRAY,
          OPERATION_C_API_ENCODE_TO_JAVA_DECODE,
          benchmarkCase,
          latestModelCount[0],
          measurement);
    }
  }

  private static BenchmarkRow runJavaToNativeDecodeNativeMemory(
      FfmBindings ffmBindings,
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) throws Throwable {
    long expectedFingerprint = BinaryModelWireCodec.fingerprint(source);
    long expectedCount = source.size();

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBytes = arena.allocate(benchmarkCase.targetBytes());
      FfmBindings.ConsumeScratch scratch = ffmBindings.newConsumeScratch(arena);

      BinaryModelWireCodec.encodeToSegment(source, nativeBytes);
      FfmBindings.DecodeResult warmupResult =
          ffmBindings.processBinaryModelBytes(nativeBytes, benchmarkCase.targetBytes(), scratch);
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            BinaryModelWireCodec.encodeToSegment(source, nativeBytes);
            FfmBindings.DecodeResult result =
                ffmBindings.processBinaryModelBytes(nativeBytes, benchmarkCase.targetBytes(), scratch);
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_BINARY_MODEL,
          BINDING_NATIVE_MEMORY,
          OPERATION_JAVA_ENCODE_TO_C_API_DECODE,
          benchmarkCase,
          source.size(),
          measurement);
    }
  }

  private static BenchmarkRow runNativeToJavaDecodeNativeMemory(FfmBindings ffmBindings, BenchmarkCase benchmarkCase)
      throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      FfmBindings.EncodeScratch scratch = ffmBindings.newEncodeScratch(arena);

      FfmBindings.EncodedBytes warmupBytes =
          ffmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes(), scratch);
      int modelCount;
      try {
        List<BinaryModel> models = BinaryModelWireCodec.decode(warmupBytes.data(), warmupBytes.size());
        verifyEncodedModels(warmupBytes, models);
        modelCount = models.size();
        consume(BinaryModelWireCodec.fingerprint(models));
      } finally {
        ffmBindings.freeBytes(warmupBytes);
      }

      int[] latestModelCount = new int[] {modelCount};
      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            FfmBindings.EncodedBytes encodedBytes =
                ffmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes(), scratch);
            try {
              List<BinaryModel> models = BinaryModelWireCodec.decode(encodedBytes.data(), encodedBytes.size());
              verifyEncodedModels(encodedBytes, models);
              latestModelCount[0] = models.size();
              return BinaryModelWireCodec.fingerprint(models);
            } finally {
              ffmBindings.freeBytes(encodedBytes);
            }
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_BINARY_MODEL,
          BINDING_NATIVE_MEMORY,
          OPERATION_C_API_ENCODE_TO_JAVA_DECODE,
          benchmarkCase,
          latestModelCount[0],
          measurement);
    }
  }

  private static BenchmarkRow runJavaToNativeDecodeU32ByteArray(
      FfmBindings ffmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) throws Throwable {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);
    long expectedCount = source.length;

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBytes = arena.allocate(benchmarkCase.targetBytes());
      FfmBindings.ConsumeScratch scratch = ffmBindings.newConsumeScratch(arena);

      byte[] warmupBytes = U32PayloadCodec.encode(source);
      copyByteArrayToNative(warmupBytes, nativeBytes);
      FfmBindings.DecodeResult warmupResult = ffmBindings.processU32Bytes(nativeBytes, warmupBytes.length, scratch);
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            byte[] bytes = U32PayloadCodec.encode(source);
            copyByteArrayToNative(bytes, nativeBytes);
            FfmBindings.DecodeResult result = ffmBindings.processU32Bytes(nativeBytes, bytes.length, scratch);
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_U32,
          BINDING_BYTE_ARRAY,
          OPERATION_JAVA_ENCODE_TO_C_API_DECODE,
          benchmarkCase,
          source.length,
          measurement);
    }
  }

  private static BenchmarkRow runNativeToJavaDecodeU32ByteArray(
      FfmBindings ffmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) throws Throwable {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);

    try (Arena arena = Arena.ofConfined()) {
      FfmBindings.EncodeScratch scratch = ffmBindings.newEncodeScratch(arena);

      FfmBindings.EncodedBytes warmupBytes = ffmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes(), scratch);
      try {
        int[] values = U32PayloadCodec.decode(copyNativeToByteArray(warmupBytes.data(), warmupBytes.size()));
        verifyDecodedU32Values(values, source.length, expectedFingerprint);
        consume(U32PayloadCodec.fingerprint(values));
      } finally {
        ffmBindings.freeBytes(warmupBytes);
      }

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            FfmBindings.EncodedBytes encodedBytes = ffmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes(), scratch);
            try {
              int[] values = U32PayloadCodec.decode(copyNativeToByteArray(encodedBytes.data(), encodedBytes.size()));
              verifyDecodedU32Values(values, source.length, expectedFingerprint);
              return U32PayloadCodec.fingerprint(values);
            } finally {
              ffmBindings.freeBytes(encodedBytes);
            }
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_U32,
          BINDING_BYTE_ARRAY,
          OPERATION_C_API_ENCODE_TO_JAVA_DECODE,
          benchmarkCase,
          source.length,
          measurement);
    }
  }

  private static BenchmarkRow runJavaToNativeDecodeU32NativeMemory(
      FfmBindings ffmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) throws Throwable {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);
    long expectedCount = source.length;

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBytes = arena.allocate(benchmarkCase.targetBytes());
      FfmBindings.ConsumeScratch scratch = ffmBindings.newConsumeScratch(arena);

      U32PayloadCodec.encodeToSegment(source, nativeBytes);
      FfmBindings.DecodeResult warmupResult = ffmBindings.processU32Bytes(nativeBytes, benchmarkCase.targetBytes(), scratch);
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            U32PayloadCodec.encodeToSegment(source, nativeBytes);
            FfmBindings.DecodeResult result = ffmBindings.processU32Bytes(nativeBytes, benchmarkCase.targetBytes(), scratch);
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_U32,
          BINDING_NATIVE_MEMORY,
          OPERATION_JAVA_ENCODE_TO_C_API_DECODE,
          benchmarkCase,
          source.length,
          measurement);
    }
  }

  private static BenchmarkRow runNativeToJavaDecodeU32NativeMemory(
      FfmBindings ffmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) throws Throwable {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);

    try (Arena arena = Arena.ofConfined()) {
      FfmBindings.EncodeScratch scratch = ffmBindings.newEncodeScratch(arena);

      FfmBindings.EncodedBytes warmupBytes = ffmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes(), scratch);
      try {
        int[] values = U32PayloadCodec.decode(warmupBytes.data(), warmupBytes.size());
        verifyDecodedU32Values(values, source.length, expectedFingerprint);
        consume(U32PayloadCodec.fingerprint(values));
      } finally {
        ffmBindings.freeBytes(warmupBytes);
      }

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            FfmBindings.EncodedBytes encodedBytes = ffmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes(), scratch);
            try {
              int[] values = U32PayloadCodec.decode(encodedBytes.data(), encodedBytes.size());
              verifyDecodedU32Values(values, source.length, expectedFingerprint);
              return U32PayloadCodec.fingerprint(values);
            } finally {
              ffmBindings.freeBytes(encodedBytes);
            }
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_U32,
          BINDING_NATIVE_MEMORY,
          OPERATION_C_API_ENCODE_TO_JAVA_DECODE,
          benchmarkCase,
          source.length,
          measurement);
    }
  }

  private static void verifyDecodeResult(
      FfmBindings.DecodeResult result,
      long expectedCount,
      long expectedFingerprint) {
    if (result.count() != expectedCount || result.fingerprint() != expectedFingerprint) {
      throw new IllegalStateException("C API decode produced an unexpected fingerprint.");
    }
  }

  private static void verifyEncodedModels(FfmBindings.EncodedBytes encodedBytes, List<BinaryModel> models) {
    long fingerprint = BinaryModelWireCodec.fingerprint(models);
    if (encodedBytes.count() != models.size() || encodedBytes.fingerprint() != fingerprint) {
      throw new IllegalStateException("Java decode produced an unexpected fingerprint.");
    }
  }

  private static void verifyDecodedU32Values(int[] values, long expectedCount, long expectedFingerprint) {
    if (values.length != expectedCount || U32PayloadCodec.fingerprint(values) != expectedFingerprint) {
      throw new IllegalStateException("Java u32 decode produced an unexpected fingerprint.");
    }
  }

  private static void copyByteArrayToNative(byte[] source, MemorySegment target) {
    MemorySegment.copy(MemorySegment.ofArray(source), 0, target, 0, source.length);
  }

  private static byte[] copyNativeToByteArray(MemorySegment source, int size) {
    byte[] bytes = new byte[size];
    MemorySegment.copy(source, 0, MemorySegment.ofArray(bytes), 0, size);
    return bytes;
  }

  private static void consume(long value) {
    sink += value;
  }

  private static void writeJsonResults(Path path, List<BenchmarkRow> rows) throws IOException {
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

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private record Arguments(Path libraryPath, Path jsonPath) {
    static Arguments parse(String[] args) {
      Path libraryPath = null;
      Path jsonPath = null;

      for (int index = 0; index < args.length; index++) {
        String arg = args[index];
        if ("--lib".equals(arg)) {
          index += 1;
          if (index >= args.length) {
            throw new IllegalArgumentException("--lib requires a path.");
          }
          libraryPath = Path.of(args[index]);
        } else if ("--json".equals(arg)) {
          index += 1;
          if (index >= args.length) {
            throw new IllegalArgumentException("--json requires a path.");
          }
          jsonPath = Path.of(args[index]);
        } else {
          throw new IllegalArgumentException("Unknown argument: " + arg);
        }
      }

      if (libraryPath == null) {
        libraryPath = defaultLibraryPath();
      }

      return new Arguments(libraryPath.toAbsolutePath().normalize(), jsonPath);
    }

    private static Path defaultLibraryPath() {
      String propertyValue = System.getProperty("ffibb.lib");
      if (propertyValue != null && !propertyValue.isBlank()) {
        return Path.of(propertyValue);
      }

      String environmentValue = System.getenv("FFIBB_LIB");
      if (environmentValue != null && !environmentValue.isBlank()) {
        return Path.of(environmentValue);
      }

      String libraryName = sharedLibraryName();
      Path cwd = Path.of(System.getProperty("user.dir"));
      List<Path> candidates = List.of(
          cwd.resolve("../../build/native-release/lib").resolve(libraryName),
          cwd.resolve("../../cmake-build-release/lib").resolve(libraryName),
          cwd.resolve("build/native-release/lib").resolve(libraryName),
          cwd.resolve("cmake-build-release/lib").resolve(libraryName),
          cwd.resolve("cmake-build-debug/lib").resolve(libraryName));

      for (Path candidate : candidates) {
        if (Files.exists(candidate)) {
          return candidate;
        }
      }

      return candidates.get(0);
    }

    private static String sharedLibraryName() {
      String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      if (osName.contains("mac")) {
        return "libffibb.dylib";
      }
      if (osName.contains("win")) {
        return "ffibb.dll";
      }
      return "libffibb.so";
    }
  }

  private record BinaryModel(int intValue, float floatValue, String stringValue) {
  }

  private static final class BinaryModelPayloadFactory {
    private BinaryModelPayloadFactory() {
    }

    static List<BinaryModel> createModelsForTargetBytes(int targetBytes) {
      if (targetBytes < RECORD_HEADER_BYTES) {
        throw new IllegalArgumentException("Target payload is too small for BinaryModel.");
      }

      ArrayList<BinaryModel> models = new ArrayList<>();
      int usedBytes = 0;
      int index = 0;

      while (usedBytes < targetBytes) {
        int remaining = targetBytes - usedBytes;
        if (remaining < RECORD_HEADER_BYTES) {
          if (models.isEmpty()) {
            throw new IllegalStateException("BinaryModel payload generation failed.");
          }

          BinaryModel last = models.remove(models.size() - 1);
          String extended = last.stringValue() + buildAsciiString(index, remaining);
          models.add(new BinaryModel(last.intValue(), last.floatValue(), extended));
          usedBytes += remaining;
          break;
        }

        int maxStringBytes = remaining - RECORD_HEADER_BYTES;
        int stringBytes = Math.min(maxStringBytes, 12 + (index % 19));
        int tailBytes = remaining - RECORD_HEADER_BYTES - stringBytes;
        if (tailBytes > 0 && tailBytes < RECORD_HEADER_BYTES) {
          stringBytes += tailBytes;
        }

        models.add(makeBinaryModel(index, stringBytes));
        usedBytes += RECORD_HEADER_BYTES + stringBytes;
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

  private static final class U32PayloadCodec {
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
      if (bytes.length != 0) {
        MemorySegment.copy(MemorySegment.ofArray(values), 0, MemorySegment.ofArray(bytes), 0, bytes.length);
      }
      return bytes;
    }

    static void encodeToSegment(int[] values, MemorySegment output) {
      long size = (long) values.length * Integer.BYTES;
      if (output.byteSize() < size) {
        throw new IllegalArgumentException("MemorySegment is too small for compact u32 payload.");
      }
      if (size != 0) {
        MemorySegment.copy(MemorySegment.ofArray(values), 0, output, 0, size);
      }
    }

    static int[] decode(byte[] bytes) {
      int[] values = new int[elementCountForBytes(bytes.length)];
      if (bytes.length != 0) {
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, MemorySegment.ofArray(values), 0, bytes.length);
      }
      return values;
    }

    static int[] decode(MemorySegment bytes, int size) {
      int[] values = new int[elementCountForBytes(size)];
      if (size != 0) {
        MemorySegment.copy(bytes, 0, MemorySegment.ofArray(values), 0, size);
      }
      return values;
    }

    static long fingerprint(int[] values) {
      if (values.length == 0) {
        return 0L;
      }

      int middle = values.length / 2;
      return values.length
          + Integer.toUnsignedLong(values[0])
          + Integer.toUnsignedLong(values[middle])
          + Integer.toUnsignedLong(values[values.length - 1]);
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

  private static final class BinaryModelWireCodec {
    private BinaryModelWireCodec() {
    }

    static byte[] encode(List<BinaryModel> models) {
      byte[] bytes = new byte[encodedSize(models)];
      int offset = 0;

      for (BinaryModel model : models) {
        writeIntLE(bytes, offset, model.intValue());
        offset += 4;
        writeIntLE(bytes, offset, Float.floatToRawIntBits(model.floatValue()));
        offset += 4;

        byte[] stringBytes = model.stringValue().getBytes(StandardCharsets.UTF_8);
        writeIntLE(bytes, offset, stringBytes.length);
        offset += 4;

        System.arraycopy(stringBytes, 0, bytes, offset, stringBytes.length);
        offset += stringBytes.length;
      }

      return bytes;
    }

    static void encodeToSegment(List<BinaryModel> models, MemorySegment output) {
      int size = encodedSize(models);
      if (output.byteSize() < size) {
        throw new IllegalArgumentException("MemorySegment is too small for compact payload.");
      }

      int offset = 0;
      for (BinaryModel model : models) {
        writeIntLE(output, offset, model.intValue());
        offset += 4;
        writeIntLE(output, offset, Float.floatToRawIntBits(model.floatValue()));
        offset += 4;

        byte[] stringBytes = model.stringValue().getBytes(StandardCharsets.UTF_8);
        writeIntLE(output, offset, stringBytes.length);
        offset += 4;

        MemorySegment.copy(MemorySegment.ofArray(stringBytes), 0, output, offset, stringBytes.length);
        offset += stringBytes.length;
      }
    }

    static List<BinaryModel> decode(byte[] bytes) {
      ArrayList<BinaryModel> models = new ArrayList<>();
      int offset = 0;

      while (offset < bytes.length) {
        if (bytes.length - offset < RECORD_HEADER_BYTES) {
          throw new IllegalArgumentException("Unexpected end of compact byte payload.");
        }

        int intValue = readIntLE(bytes, offset);
        offset += 4;

        float floatValue = Float.intBitsToFloat(readIntLE(bytes, offset));
        offset += 4;

        int stringSize = readIntLE(bytes, offset);
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

    static List<BinaryModel> decode(MemorySegment bytes, int size) {
      ArrayList<BinaryModel> models = new ArrayList<>();
      int offset = 0;

      while (offset < size) {
        if (size - offset < RECORD_HEADER_BYTES) {
          throw new IllegalArgumentException("Unexpected end of compact byte payload.");
        }

        int intValue = readIntLE(bytes, offset);
        offset += 4;

        float floatValue = Float.intBitsToFloat(readIntLE(bytes, offset));
        offset += 4;

        int stringSize = readIntLE(bytes, offset);
        offset += 4;

        if (stringSize < 0 || size - offset < stringSize) {
          throw new IllegalArgumentException("String size exceeds compact payload size.");
        }

        byte[] stringBytes = new byte[stringSize];
        MemorySegment.copy(bytes.asSlice(offset, stringSize), 0, MemorySegment.ofArray(stringBytes), 0, stringSize);
        offset += stringSize;
        models.add(new BinaryModel(intValue, floatValue, new String(stringBytes, StandardCharsets.UTF_8)));
      }

      return models;
    }

    static int encodedSize(List<BinaryModel> models) {
      int totalBytes = 0;
      for (BinaryModel model : models) {
        totalBytes += RECORD_HEADER_BYTES + model.stringValue().getBytes(StandardCharsets.UTF_8).length;
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

    private static void writeIntLE(byte[] bytes, int offset, int value) {
      bytes[offset] = (byte) (value & 0xFF);
      bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
      bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
      bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeIntLE(MemorySegment segment, int offset, int value) {
      segment.set(ValueLayout.JAVA_BYTE, offset, (byte) (value & 0xFF));
      segment.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) ((value >>> 8) & 0xFF));
      segment.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) ((value >>> 16) & 0xFF));
      segment.set(ValueLayout.JAVA_BYTE, offset + 3, (byte) ((value >>> 24) & 0xFF));
    }

    private static int readIntLE(byte[] bytes, int offset) {
      return (bytes[offset] & 0xFF) |
          ((bytes[offset + 1] & 0xFF) << 8) |
          ((bytes[offset + 2] & 0xFF) << 16) |
          ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int readIntLE(MemorySegment segment, int offset) {
      return (segment.get(ValueLayout.JAVA_BYTE, offset) & 0xFF) |
          ((segment.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF) << 8) |
          ((segment.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF) << 16) |
          ((segment.get(ValueLayout.JAVA_BYTE, offset + 3) & 0xFF) << 24);
    }
  }

  private static final class BenchmarkTimer {
    private BenchmarkTimer() {
    }

    static BenchmarkMeasurement measure(int iterations, int warmupRounds, BenchmarkTask task) throws Throwable {
      for (int index = 0; index < warmupRounds; index++) {
        consume(task.run());
      }

      double totalMs = 0.0;
      double minMs = Double.MAX_VALUE;
      double maxMs = 0.0;

      for (int index = 0; index < iterations; index++) {
        long begin = System.nanoTime();
        long value = task.run();
        long end = System.nanoTime();

        consume(value);
        double elapsedMs = (end - begin) / 1_000_000.0;
        totalMs += elapsedMs;
        minMs = Math.min(minMs, elapsedMs);
        maxMs = Math.max(maxMs, elapsedMs);
      }

      return new BenchmarkMeasurement(totalMs / iterations, minMs, maxMs);
    }
  }

  @FunctionalInterface
  private interface BenchmarkTask {
    long run() throws Throwable;
  }

  private record BenchmarkCase(String name, int targetBytes, int iterations) {
  }

  private record BenchmarkMeasurement(double averageMs, double minMs, double maxMs) {
  }

  private record BenchmarkRow(
      String payloadFamily,
      String binding,
      String operation,
      BenchmarkCase benchmarkCase,
      int elementCount,
      BenchmarkMeasurement measurement) {
  }

  private static final class BenchmarkReportFormatter {
    private BenchmarkReportFormatter() {
    }

    static String format(
        Path libraryPath,
        List<BenchmarkCase> cases,
        List<Integer> modelCounts,
        List<Integer> u32ElementCounts,
        List<BenchmarkRow> rows) {
      StringBuilder report = new StringBuilder();

      report.append("ffi-binary-bench Java 22 FFM benchmark\n");
      report.append("Payload families: BinaryModel, u32 baseline\n");
      report.append("Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes\n");
      report.append("Library: ").append(libraryPath).append('\n');
      report.append("JVM: ").append(System.getProperty("java.version")).append('\n');
      report.append("Operations:\n");
      report.append("  BinaryModel: List<BinaryModel> -> compact bytes -> FFM -> C API decode\n");
      report.append("  BinaryModel: C API encode -> FFM -> compact bytes -> List<BinaryModel>\n");
      report.append("  u32: int[] -> compact bytes -> FFM -> C API decode\n");
      report.append("  u32: C API encode -> FFM -> compact bytes -> int[]\n");
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

      appendRowsForPayloadFamily(report, rows, PAYLOAD_FAMILY_BINARY_MODEL, "BinaryModel");
      appendRowsForPayloadFamily(report, rows, PAYLOAD_FAMILY_U32, "u32 Baseline");

      report.append("\nSink: ").append(sink).append('\n');
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
  }
}
