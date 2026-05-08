package com.ffibb.jvmwasm;

import java.util.ArrayList;
import java.util.List;

public final class BenchmarkMain {
  private static final String PAYLOAD_FAMILY_BINARY_MODEL = "BinaryModel";
  private static final String PAYLOAD_FAMILY_U32 = "u32";
  private static final String BINDING_BYTE_ARRAY = "byte_array";
  private static final String BINDING_WASM_MEMORY = "wasm_memory";
  private static final String OPERATION_JVM_ENCODE_TO_WASM_C_API_DECODE = "jvm_encode_to_wasm_c_api_decode";
  private static final String OPERATION_WASM_C_API_ENCODE_TO_JVM_DECODE = "wasm_c_api_encode_to_jvm_decode";
  private static final BenchmarkCase[] CASES = new BenchmarkCase[] {
      new BenchmarkCase("10 KiB", 10 * 1024, 5000),
      new BenchmarkCase("200 KiB", 200 * 1024, 1000),
      new BenchmarkCase("1 MiB", 1024 * 1024, 200)
  };
  private static final int WARMUP_ROUNDS = 10;
  private static volatile long sink;

  private BenchmarkMain() {
  }

  public static void main(String[] args) throws Throwable {
    Arguments arguments = Arguments.parse(args);

    try (WasmBindings wasmBindings = arguments.runtime().load(arguments.wasmPath())) {
      ArrayList<BenchmarkRow> rows = new ArrayList<>();
      ArrayList<Integer> modelCounts = new ArrayList<>();
      ArrayList<Integer> u32ElementCounts = new ArrayList<>();
      sink = 0L;

      for (BenchmarkCase benchmarkCase : CASES) {
        List<BinaryModel> source = BinaryModelPayloadFactory.createModelsForTargetBytes(benchmarkCase.targetBytes());
        int[] u32Source = U32PayloadCodec.createValuesForTargetBytes(benchmarkCase.targetBytes());
        modelCounts.add(source.size());
        u32ElementCounts.add(u32Source.length);

        rows.add(runJvmToWasmDecodeByteArray(wasmBindings, benchmarkCase, source));
        rows.add(runWasmToJvmDecodeByteArray(wasmBindings, benchmarkCase, source));
        rows.add(runJvmToWasmDecodeWasmMemory(wasmBindings, benchmarkCase, source));
        rows.add(runWasmToJvmDecodeWasmMemory(wasmBindings, benchmarkCase, source));
        rows.add(runJvmToWasmDecodeU32ByteArray(wasmBindings, benchmarkCase, u32Source));
        rows.add(runWasmToJvmDecodeU32ByteArray(wasmBindings, benchmarkCase, u32Source));
        rows.add(runJvmToWasmDecodeU32WasmMemory(wasmBindings, benchmarkCase, u32Source));
        rows.add(runWasmToJvmDecodeU32WasmMemory(wasmBindings, benchmarkCase, u32Source));
      }

      String report = BenchmarkReportFormatter.format(
          arguments.wasmPath(),
          arguments.runtime(),
          List.of(CASES),
          modelCounts,
          u32ElementCounts,
          rows,
          sink);
      System.out.print(report);

      if (arguments.jsonPath() != null) {
        BenchmarkReportFormatter.writeJsonResults(arguments.jsonPath(), rows);
        System.out.println();
        System.out.println("JSON: " + arguments.jsonPath());
      }
    }
  }

  private static BenchmarkRow runJvmToWasmDecodeByteArray(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) {
    long expectedFingerprint = BinaryModelWireCodec.fingerprint(source);
    long expectedCount = source.size();
    int pointer = wasmBindings.malloc(benchmarkCase.targetBytes());

    try {
      byte[] warmupBytes = BinaryModelWireCodec.encode(source);
      wasmBindings.writeBytes(pointer, warmupBytes);
      WasmBindings.DecodeResult warmupResult = wasmBindings.processBinaryModelBytes(pointer, warmupBytes.length);
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            byte[] bytes = BinaryModelWireCodec.encode(source);
            wasmBindings.writeBytes(pointer, bytes);
            WasmBindings.DecodeResult result = wasmBindings.processBinaryModelBytes(pointer, bytes.length);
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_BINARY_MODEL,
          BINDING_BYTE_ARRAY,
          OPERATION_JVM_ENCODE_TO_WASM_C_API_DECODE,
          benchmarkCase,
          source.size(),
          measurement);
    } finally {
      wasmBindings.free(pointer);
    }
  }

  private static BenchmarkRow runWasmToJvmDecodeByteArray(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) {
    long expectedFingerprint = BinaryModelWireCodec.fingerprint(source);

    WasmBindings.EncodedBytes warmupBytes = wasmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes());
    int modelCount;
    try {
      byte[] bytes = wasmBindings.readBytes(warmupBytes.dataPointer(), warmupBytes.size());
      List<BinaryModel> models = BinaryModelWireCodec.decode(bytes);
      verifyEncodedModels(warmupBytes, models, expectedFingerprint);
      modelCount = models.size();
      consume(BinaryModelWireCodec.fingerprint(models));
    } finally {
      wasmBindings.freeBytes(warmupBytes);
    }

    int[] latestModelCount = new int[] {modelCount};
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations(),
        WARMUP_ROUNDS,
        () -> {
          WasmBindings.EncodedBytes encodedBytes = wasmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes());
          try {
            byte[] bytes = wasmBindings.readBytes(encodedBytes.dataPointer(), encodedBytes.size());
            List<BinaryModel> models = BinaryModelWireCodec.decode(bytes);
            verifyEncodedModels(encodedBytes, models, expectedFingerprint);
            latestModelCount[0] = models.size();
            return BinaryModelWireCodec.fingerprint(models);
          } finally {
            wasmBindings.freeBytes(encodedBytes);
          }
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_BYTE_ARRAY,
        OPERATION_WASM_C_API_ENCODE_TO_JVM_DECODE,
        benchmarkCase,
        latestModelCount[0],
        measurement);
  }

  private static BenchmarkRow runJvmToWasmDecodeWasmMemory(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) {
    long expectedFingerprint = BinaryModelWireCodec.fingerprint(source);
    long expectedCount = source.size();
    int pointer = wasmBindings.malloc(benchmarkCase.targetBytes());

    try {
      BinaryModelWireCodec.encodeToMemory(source, wasmBindings.memory(), pointer);
      WasmBindings.DecodeResult warmupResult =
          wasmBindings.processBinaryModelBytes(pointer, benchmarkCase.targetBytes());
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            BinaryModelWireCodec.encodeToMemory(source, wasmBindings.memory(), pointer);
            WasmBindings.DecodeResult result = wasmBindings.processBinaryModelBytes(pointer, benchmarkCase.targetBytes());
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_BINARY_MODEL,
          BINDING_WASM_MEMORY,
          OPERATION_JVM_ENCODE_TO_WASM_C_API_DECODE,
          benchmarkCase,
          source.size(),
          measurement);
    } finally {
      wasmBindings.free(pointer);
    }
  }

  private static BenchmarkRow runWasmToJvmDecodeWasmMemory(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) {
    long expectedFingerprint = BinaryModelWireCodec.fingerprint(source);

    WasmBindings.EncodedBytes warmupBytes = wasmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes());
    int modelCount;
    try {
      List<BinaryModel> models =
          BinaryModelWireCodec.decode(wasmBindings.memory(), warmupBytes.dataPointer(), warmupBytes.size());
      verifyEncodedModels(warmupBytes, models, expectedFingerprint);
      modelCount = models.size();
      consume(BinaryModelWireCodec.fingerprint(models));
    } finally {
      wasmBindings.freeBytes(warmupBytes);
    }

    int[] latestModelCount = new int[] {modelCount};
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations(),
        WARMUP_ROUNDS,
        () -> {
          WasmBindings.EncodedBytes encodedBytes = wasmBindings.buildSampleBinaryModelBytes(benchmarkCase.targetBytes());
          try {
            List<BinaryModel> models =
                BinaryModelWireCodec.decode(wasmBindings.memory(), encodedBytes.dataPointer(), encodedBytes.size());
            verifyEncodedModels(encodedBytes, models, expectedFingerprint);
            latestModelCount[0] = models.size();
            return BinaryModelWireCodec.fingerprint(models);
          } finally {
            wasmBindings.freeBytes(encodedBytes);
          }
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_WASM_MEMORY,
        OPERATION_WASM_C_API_ENCODE_TO_JVM_DECODE,
        benchmarkCase,
        latestModelCount[0],
        measurement);
  }

  private static BenchmarkRow runJvmToWasmDecodeU32ByteArray(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);
    long expectedCount = source.length;
    int pointer = wasmBindings.malloc(benchmarkCase.targetBytes());

    try {
      byte[] warmupBytes = U32PayloadCodec.encode(source);
      wasmBindings.writeBytes(pointer, warmupBytes);
      WasmBindings.DecodeResult warmupResult = wasmBindings.processU32Bytes(pointer, warmupBytes.length);
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            byte[] bytes = U32PayloadCodec.encode(source);
            wasmBindings.writeBytes(pointer, bytes);
            WasmBindings.DecodeResult result = wasmBindings.processU32Bytes(pointer, bytes.length);
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_U32,
          BINDING_BYTE_ARRAY,
          OPERATION_JVM_ENCODE_TO_WASM_C_API_DECODE,
          benchmarkCase,
          source.length,
          measurement);
    } finally {
      wasmBindings.free(pointer);
    }
  }

  private static BenchmarkRow runWasmToJvmDecodeU32ByteArray(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);

    WasmBindings.EncodedBytes warmupBytes = wasmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes());
    try {
      int[] values = U32PayloadCodec.decode(wasmBindings.readBytes(warmupBytes.dataPointer(), warmupBytes.size()));
      verifyDecodedU32Values(values, source.length, expectedFingerprint, warmupBytes.fingerprint());
      consume(U32PayloadCodec.fingerprint(values));
    } finally {
      wasmBindings.freeBytes(warmupBytes);
    }

    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations(),
        WARMUP_ROUNDS,
        () -> {
          WasmBindings.EncodedBytes encodedBytes = wasmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes());
          try {
            int[] values = U32PayloadCodec.decode(wasmBindings.readBytes(encodedBytes.dataPointer(), encodedBytes.size()));
            verifyDecodedU32Values(values, source.length, expectedFingerprint, encodedBytes.fingerprint());
            return U32PayloadCodec.fingerprint(values);
          } finally {
            wasmBindings.freeBytes(encodedBytes);
          }
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_BYTE_ARRAY,
        OPERATION_WASM_C_API_ENCODE_TO_JVM_DECODE,
        benchmarkCase,
        source.length,
        measurement);
  }

  private static BenchmarkRow runJvmToWasmDecodeU32WasmMemory(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);
    long expectedCount = source.length;
    int pointer = wasmBindings.malloc(benchmarkCase.targetBytes());

    try {
      U32PayloadCodec.encodeToMemory(source, wasmBindings.memory(), pointer);
      WasmBindings.DecodeResult warmupResult = wasmBindings.processU32Bytes(pointer, benchmarkCase.targetBytes());
      verifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
      consume(warmupResult.combinedValue());

      BenchmarkMeasurement measurement = BenchmarkTimer.measure(
          benchmarkCase.iterations(),
          WARMUP_ROUNDS,
          () -> {
            U32PayloadCodec.encodeToMemory(source, wasmBindings.memory(), pointer);
            WasmBindings.DecodeResult result = wasmBindings.processU32Bytes(pointer, benchmarkCase.targetBytes());
            verifyDecodeResult(result, expectedCount, expectedFingerprint);
            return result.combinedValue();
          });

      return new BenchmarkRow(
          PAYLOAD_FAMILY_U32,
          BINDING_WASM_MEMORY,
          OPERATION_JVM_ENCODE_TO_WASM_C_API_DECODE,
          benchmarkCase,
          source.length,
          measurement);
    } finally {
      wasmBindings.free(pointer);
    }
  }

  private static BenchmarkRow runWasmToJvmDecodeU32WasmMemory(
      WasmBindings wasmBindings,
      BenchmarkCase benchmarkCase,
      int[] source) {
    long expectedFingerprint = U32PayloadCodec.fingerprint(source);

    WasmBindings.EncodedBytes warmupBytes = wasmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes());
    try {
      int[] values = U32PayloadCodec.decode(wasmBindings.memory(), warmupBytes.dataPointer(), warmupBytes.size());
      verifyDecodedU32Values(values, source.length, expectedFingerprint, warmupBytes.fingerprint());
      consume(U32PayloadCodec.fingerprint(values));
    } finally {
      wasmBindings.freeBytes(warmupBytes);
    }

    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations(),
        WARMUP_ROUNDS,
        () -> {
          WasmBindings.EncodedBytes encodedBytes = wasmBindings.buildSampleU32Bytes(benchmarkCase.targetBytes());
          try {
            int[] values = U32PayloadCodec.decode(wasmBindings.memory(), encodedBytes.dataPointer(), encodedBytes.size());
            verifyDecodedU32Values(values, source.length, expectedFingerprint, encodedBytes.fingerprint());
            return U32PayloadCodec.fingerprint(values);
          } finally {
            wasmBindings.freeBytes(encodedBytes);
          }
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_WASM_MEMORY,
        OPERATION_WASM_C_API_ENCODE_TO_JVM_DECODE,
        benchmarkCase,
        source.length,
        measurement);
  }

  private static void verifyDecodeResult(
      WasmBindings.DecodeResult result,
      long expectedCount,
      long expectedFingerprint) {
    if (result.count() != expectedCount || result.fingerprint() != expectedFingerprint) {
      throw new IllegalStateException(
          "Wasm C API decode produced an unexpected fingerprint: count="
              + result.count()
              + " expected_count="
              + expectedCount
              + " fingerprint="
              + Long.toUnsignedString(result.fingerprint())
              + " expected_fingerprint="
              + Long.toUnsignedString(expectedFingerprint));
    }
  }

  private static void verifyEncodedModels(
      WasmBindings.EncodedBytes encodedBytes,
      List<BinaryModel> models,
      long expectedFingerprint) {
    long fingerprint = BinaryModelWireCodec.fingerprint(models);
    if (encodedBytes.count() != models.size()
        || encodedBytes.fingerprint() != fingerprint
        || fingerprint != expectedFingerprint) {
      throw new IllegalStateException(
          "JVM BinaryModel decode produced an unexpected fingerprint: count="
              + encodedBytes.count()
              + " expected_count="
              + models.size()
              + " fingerprint="
              + Long.toUnsignedString(encodedBytes.fingerprint())
              + " decoded_fingerprint="
              + Long.toUnsignedString(fingerprint)
              + " expected_fingerprint="
              + Long.toUnsignedString(expectedFingerprint));
    }
  }

  private static void verifyDecodedU32Values(
      int[] values,
      long expectedCount,
      long expectedFingerprint,
      long encodedFingerprint) {
    long fingerprint = U32PayloadCodec.fingerprint(values);
    if (values.length != expectedCount || fingerprint != expectedFingerprint || fingerprint != encodedFingerprint) {
      throw new IllegalStateException(
          "JVM u32 decode produced an unexpected fingerprint: count="
              + values.length
              + " expected_count="
              + expectedCount
              + " fingerprint="
              + Long.toUnsignedString(fingerprint)
              + " expected_fingerprint="
              + Long.toUnsignedString(expectedFingerprint)
              + " encoded_fingerprint="
              + Long.toUnsignedString(encodedFingerprint));
    }
  }

  static void consume(long value) {
    sink += value;
  }

}
