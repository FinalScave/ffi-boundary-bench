package com.ffibb.android;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AndroidBenchmarkRunner {
  private static final String PAYLOAD_FAMILY_BINARY_MODEL = "BinaryModel";
  private static final String PAYLOAD_FAMILY_U32 = "u32";
  private static final String BINDING_BYTE_ARRAY = "byte_array";
  private static final String BINDING_DIRECT_BYTE_BUFFER = "direct_byte_buffer";
  private static final String BINDING_DIRECT_BYTE_BUFFER_FAST_NATIVE = "direct_byte_buffer_fast_native";
  private static final String OPERATION_ANDROID_ENCODE_TO_C_API_DECODE = "android_encode_to_c_api_decode";
  private static final String OPERATION_C_API_ENCODE_TO_ANDROID_DECODE = "c_api_encode_to_android_decode";
  private static final BenchmarkCase[] CASES = new BenchmarkCase[] {
      new BenchmarkCase("10 KiB", 10 * 1024, 100),
      new BenchmarkCase("200 KiB", 200 * 1024, 30),
      new BenchmarkCase("1 MiB", 1024 * 1024, 10)
  };
  private static final int WARMUP_ROUNDS = 3;

  private AndroidBenchmarkRunner() {
  }

  static String runAll() {
    BenchmarkSink.reset();

    ArrayList<BenchmarkRow> rows = new ArrayList<>();
    ArrayList<Integer> modelCounts = new ArrayList<>();
    ArrayList<Integer> u32ElementCounts = new ArrayList<>();

    for (BenchmarkCase benchmarkCase : CASES) {
      List<BinaryModel> source = BinaryModelPayloadFactory.createModelsForTargetBytes(benchmarkCase.targetBytes);
      int[] u32Source = U32PayloadCodec.createValuesForTargetBytes(benchmarkCase.targetBytes);
      modelCounts.add(source.size());
      u32ElementCounts.add(u32Source.length);

      rows.add(runAndroidToNativeDecode(benchmarkCase, source));
      rows.add(runNativeToAndroidDecode(benchmarkCase));
      rows.add(runAndroidToNativeDecodeDirectBuffer(benchmarkCase, source));
      rows.add(runNativeToAndroidDecodeDirectBuffer(benchmarkCase));
      rows.add(runAndroidToNativeDecodeDirectBufferFast(benchmarkCase, source));
      rows.add(runNativeToAndroidDecodeDirectBufferFast(benchmarkCase));
      rows.add(runAndroidU32ToNativeDecode(benchmarkCase, u32Source));
      rows.add(runNativeToAndroidU32Decode(benchmarkCase));
      rows.add(runAndroidU32ToNativeDecodeDirectBuffer(benchmarkCase, u32Source));
      rows.add(runNativeToAndroidU32DecodeDirectBuffer(benchmarkCase));
      rows.add(runAndroidU32ToNativeDecodeDirectBufferFast(benchmarkCase, u32Source));
      rows.add(runNativeToAndroidU32DecodeDirectBufferFast(benchmarkCase));
    }

    return BenchmarkReportFormatter.format(Arrays.asList(CASES), modelCounts, u32ElementCounts, rows);
  }

  private static BenchmarkRow runAndroidToNativeDecode(BenchmarkCase benchmarkCase, List<BinaryModel> source) {
    byte[] warmupBytes = BinaryModelWireCodec.encode(source);
    BenchmarkSink.add(BenchmarkBridge.nativeProcessBinaryModels(warmupBytes));

    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          byte[] bytes = BinaryModelWireCodec.encode(source);
          return BenchmarkBridge.nativeProcessBinaryModels(bytes);
        });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_BYTE_ARRAY,
        OPERATION_ANDROID_ENCODE_TO_C_API_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        source.size(),
        measurement);
  }

  private static BenchmarkRow runNativeToAndroidDecode(BenchmarkCase benchmarkCase) {
    byte[] warmupBytes = BenchmarkBridge.nativeBuildSampleBinaryModels(benchmarkCase.targetBytes);
    List<BinaryModel> warmupModels = BinaryModelWireCodec.decode(warmupBytes);
    BenchmarkSink.add(BinaryModelWireCodec.fingerprint(warmupModels));

    int[] modelCount = new int[] {warmupModels.size()};
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          byte[] bytes = BenchmarkBridge.nativeBuildSampleBinaryModels(benchmarkCase.targetBytes);
          List<BinaryModel> models = BinaryModelWireCodec.decode(bytes);
          modelCount[0] = models.size();
          return BinaryModelWireCodec.fingerprint(models);
        });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_BYTE_ARRAY,
        OPERATION_C_API_ENCODE_TO_ANDROID_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        modelCount[0],
        measurement);
  }

  private static BenchmarkRow runAndroidToNativeDecodeDirectBuffer(
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BinaryModelWireCodec.encodeToBuffer(source, warmupBuffer);
    BenchmarkSink.add(BenchmarkBridge.nativeProcessBinaryModelsFromDirectBuffer(
        warmupBuffer,
        warmupBuffer.remaining()));

    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          BinaryModelWireCodec.encodeToBuffer(source, buffer);
          return BenchmarkBridge.nativeProcessBinaryModelsFromDirectBuffer(buffer, buffer.remaining());
        });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_DIRECT_BYTE_BUFFER,
        OPERATION_ANDROID_ENCODE_TO_C_API_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        source.size(),
        measurement);
  }

  private static BenchmarkRow runNativeToAndroidDecodeDirectBuffer(BenchmarkCase benchmarkCase) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    int warmupBytes = fillDirectBufferFromNative(benchmarkCase.targetBytes, warmupBuffer, false);
    List<BinaryModel> warmupModels = BinaryModelWireCodec.decode(bufferSlice(warmupBuffer, warmupBytes));
    BenchmarkSink.add(BinaryModelWireCodec.fingerprint(warmupModels));

    int[] modelCount = new int[] {warmupModels.size()};
    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          int bytesWritten = fillDirectBufferFromNative(benchmarkCase.targetBytes, buffer, false);
          List<BinaryModel> models = BinaryModelWireCodec.decode(bufferSlice(buffer, bytesWritten));
          modelCount[0] = models.size();
          return BinaryModelWireCodec.fingerprint(models);
        });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_DIRECT_BYTE_BUFFER,
        OPERATION_C_API_ENCODE_TO_ANDROID_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        modelCount[0],
        measurement);
  }

  private static BenchmarkRow runAndroidToNativeDecodeDirectBufferFast(
      BenchmarkCase benchmarkCase,
      List<BinaryModel> source) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BinaryModelWireCodec.encodeToBuffer(source, warmupBuffer);
    BenchmarkSink.add(BenchmarkBridge.nativeProcessBinaryModelsFromDirectBufferFast(
        warmupBuffer,
        warmupBuffer.remaining()));

    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          BinaryModelWireCodec.encodeToBuffer(source, buffer);
          return BenchmarkBridge.nativeProcessBinaryModelsFromDirectBufferFast(buffer, buffer.remaining());
        });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_DIRECT_BYTE_BUFFER_FAST_NATIVE,
        OPERATION_ANDROID_ENCODE_TO_C_API_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        source.size(),
        measurement);
  }

  private static BenchmarkRow runNativeToAndroidDecodeDirectBufferFast(BenchmarkCase benchmarkCase) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    int warmupBytes = fillDirectBufferFromNative(benchmarkCase.targetBytes, warmupBuffer, true);
    List<BinaryModel> warmupModels = BinaryModelWireCodec.decode(bufferSlice(warmupBuffer, warmupBytes));
    BenchmarkSink.add(BinaryModelWireCodec.fingerprint(warmupModels));

    int[] modelCount = new int[] {warmupModels.size()};
    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          int bytesWritten = fillDirectBufferFromNative(benchmarkCase.targetBytes, buffer, true);
          List<BinaryModel> models = BinaryModelWireCodec.decode(bufferSlice(buffer, bytesWritten));
          modelCount[0] = models.size();
          return BinaryModelWireCodec.fingerprint(models);
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_BINARY_MODEL,
        BINDING_DIRECT_BYTE_BUFFER_FAST_NATIVE,
        OPERATION_C_API_ENCODE_TO_ANDROID_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        modelCount[0],
        measurement);
  }

  private static BenchmarkRow runAndroidU32ToNativeDecode(BenchmarkCase benchmarkCase, int[] source) {
    byte[] warmupBytes = U32PayloadCodec.encode(source);
    BenchmarkSink.add(BenchmarkBridge.nativeProcessU32(warmupBytes));

    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          byte[] bytes = U32PayloadCodec.encode(source);
          return BenchmarkBridge.nativeProcessU32(bytes);
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_BYTE_ARRAY,
        OPERATION_ANDROID_ENCODE_TO_C_API_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        source.length,
        measurement);
  }

  private static BenchmarkRow runNativeToAndroidU32Decode(BenchmarkCase benchmarkCase) {
    byte[] warmupBytes = BenchmarkBridge.nativeBuildSampleU32(benchmarkCase.targetBytes);
    int[] warmupValues = U32PayloadCodec.decode(warmupBytes);
    BenchmarkSink.add(U32PayloadCodec.fingerprint(warmupValues));

    int[] elementCount = new int[] {warmupValues.length};
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          byte[] bytes = BenchmarkBridge.nativeBuildSampleU32(benchmarkCase.targetBytes);
          int[] values = U32PayloadCodec.decode(bytes);
          elementCount[0] = values.length;
          return U32PayloadCodec.fingerprint(values);
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_BYTE_ARRAY,
        OPERATION_C_API_ENCODE_TO_ANDROID_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        elementCount[0],
        measurement);
  }

  private static BenchmarkRow runAndroidU32ToNativeDecodeDirectBuffer(BenchmarkCase benchmarkCase, int[] source) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    U32PayloadCodec.encodeToBuffer(source, warmupBuffer);
    BenchmarkSink.add(BenchmarkBridge.nativeProcessU32FromDirectBuffer(warmupBuffer, warmupBuffer.remaining()));

    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          U32PayloadCodec.encodeToBuffer(source, buffer);
          return BenchmarkBridge.nativeProcessU32FromDirectBuffer(buffer, buffer.remaining());
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_DIRECT_BYTE_BUFFER,
        OPERATION_ANDROID_ENCODE_TO_C_API_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        source.length,
        measurement);
  }

  private static BenchmarkRow runNativeToAndroidU32DecodeDirectBuffer(BenchmarkCase benchmarkCase) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    int warmupBytes = fillDirectBufferFromNativeU32(benchmarkCase.targetBytes, warmupBuffer, false);
    int[] warmupValues = U32PayloadCodec.decode(bufferSlice(warmupBuffer, warmupBytes));
    BenchmarkSink.add(U32PayloadCodec.fingerprint(warmupValues));

    int[] elementCount = new int[] {warmupValues.length};
    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          int bytesWritten = fillDirectBufferFromNativeU32(benchmarkCase.targetBytes, buffer, false);
          int[] values = U32PayloadCodec.decode(bufferSlice(buffer, bytesWritten));
          elementCount[0] = values.length;
          return U32PayloadCodec.fingerprint(values);
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_DIRECT_BYTE_BUFFER,
        OPERATION_C_API_ENCODE_TO_ANDROID_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        elementCount[0],
        measurement);
  }

  private static BenchmarkRow runAndroidU32ToNativeDecodeDirectBufferFast(BenchmarkCase benchmarkCase, int[] source) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    U32PayloadCodec.encodeToBuffer(source, warmupBuffer);
    BenchmarkSink.add(BenchmarkBridge.nativeProcessU32FromDirectBufferFast(warmupBuffer, warmupBuffer.remaining()));

    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          U32PayloadCodec.encodeToBuffer(source, buffer);
          return BenchmarkBridge.nativeProcessU32FromDirectBufferFast(buffer, buffer.remaining());
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_DIRECT_BYTE_BUFFER_FAST_NATIVE,
        OPERATION_ANDROID_ENCODE_TO_C_API_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        source.length,
        measurement);
  }

  private static BenchmarkRow runNativeToAndroidU32DecodeDirectBufferFast(BenchmarkCase benchmarkCase) {
    ByteBuffer warmupBuffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    int warmupBytes = fillDirectBufferFromNativeU32(benchmarkCase.targetBytes, warmupBuffer, true);
    int[] warmupValues = U32PayloadCodec.decode(bufferSlice(warmupBuffer, warmupBytes));
    BenchmarkSink.add(U32PayloadCodec.fingerprint(warmupValues));

    int[] elementCount = new int[] {warmupValues.length};
    ByteBuffer buffer = ByteBuffer.allocateDirect(benchmarkCase.targetBytes);
    BenchmarkMeasurement measurement = BenchmarkTimer.measure(
        benchmarkCase.iterations,
        WARMUP_ROUNDS,
        () -> {
          int bytesWritten = fillDirectBufferFromNativeU32(benchmarkCase.targetBytes, buffer, true);
          int[] values = U32PayloadCodec.decode(bufferSlice(buffer, bytesWritten));
          elementCount[0] = values.length;
          return U32PayloadCodec.fingerprint(values);
        });

    return new BenchmarkRow(
        PAYLOAD_FAMILY_U32,
        BINDING_DIRECT_BYTE_BUFFER_FAST_NATIVE,
        OPERATION_C_API_ENCODE_TO_ANDROID_DECODE,
        benchmarkCase.name,
        benchmarkCase.targetBytes,
        benchmarkCase.iterations,
        elementCount[0],
        measurement);
  }

  private static int fillDirectBufferFromNative(int targetBytes, ByteBuffer output, boolean fastNative) {
    output.clear();
    int bytesWritten;
    if (fastNative) {
      bytesWritten = BenchmarkBridge.nativeBuildSampleBinaryModelsToDirectBufferFast(targetBytes, output);
    } else {
      bytesWritten = BenchmarkBridge.nativeBuildSampleBinaryModelsToDirectBuffer(targetBytes, output);
    }

    if (bytesWritten < 0 || bytesWritten > output.capacity()) {
      throw new IllegalStateException("Native direct buffer encode returned an invalid byte count.");
    }

    return bytesWritten;
  }

  private static int fillDirectBufferFromNativeU32(int targetBytes, ByteBuffer output, boolean fastNative) {
    output.clear();
    int bytesWritten;
    if (fastNative) {
      bytesWritten = BenchmarkBridge.nativeBuildSampleU32ToDirectBufferFast(targetBytes, output);
    } else {
      bytesWritten = BenchmarkBridge.nativeBuildSampleU32ToDirectBuffer(targetBytes, output);
    }

    if (bytesWritten < 0 || bytesWritten > output.capacity()) {
      throw new IllegalStateException("Native direct buffer u32 encode returned an invalid byte count.");
    }

    return bytesWritten;
  }

  private static ByteBuffer bufferSlice(ByteBuffer buffer, int bytesWritten) {
    ByteBuffer input = buffer.duplicate();
    input.clear();
    input.limit(bytesWritten);
    return input;
  }

}
