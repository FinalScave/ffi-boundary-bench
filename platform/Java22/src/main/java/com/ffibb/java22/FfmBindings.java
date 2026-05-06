package com.ffibb.java22;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

final class FfmBindings {
  private static final long ADDRESS_BYTES = ValueLayout.ADDRESS.byteSize();
  private static final long OWNED_BYTES_BYTES = ADDRESS_BYTES + ValueLayout.JAVA_LONG.byteSize();
  private final MethodHandle processU32Bytes;
  private final MethodHandle buildSampleU32Bytes;
  private final MethodHandle processBinaryModelBytes;
  private final MethodHandle buildSampleBinaryModelBytes;
  private final MethodHandle freeBytes;

  private FfmBindings(SymbolLookup lookup) {
    Linker linker = Linker.nativeLinker();
    processU32Bytes = downcall(
      linker,
      lookup,
      "ffibb_process_u32_bytes",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS));
    buildSampleU32Bytes = downcall(
      linker,
      lookup,
      "ffibb_build_sample_u32_bytes",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS));
    processBinaryModelBytes = downcall(
        linker,
        lookup,
        "ffibb_process_binary_model_bytes",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS));
    buildSampleBinaryModelBytes = downcall(
        linker,
        lookup,
        "ffibb_build_sample_binary_model_bytes",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS));
    freeBytes = downcall(
        linker,
        lookup,
        "ffibb_free_bytes",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
  }

  static FfmBindings load(Path libraryPath) {
    System.load(libraryPath.toString());
    return new FfmBindings(SymbolLookup.loaderLookup());
  }

  ConsumeScratch newConsumeScratch(Arena arena) {
    return new ConsumeScratch(
        arena.allocate(ValueLayout.JAVA_LONG),
        arena.allocate(ValueLayout.JAVA_LONG));
  }

  EncodeScratch newEncodeScratch(Arena arena) {
    return new EncodeScratch(
        arena.allocate(OWNED_BYTES_BYTES, ADDRESS_BYTES),
        arena.allocate(ValueLayout.JAVA_LONG),
        arena.allocate(ValueLayout.JAVA_LONG));
  }

  DecodeResult processU32Bytes(MemorySegment data, long size, ConsumeScratch scratch) throws Throwable {
    int status = (int) processU32Bytes.invokeExact(data, size, scratch.outFingerprint());
    checkStatus(status, "C API u32 decode failed");
    return new DecodeResult(
        size / Integer.BYTES,
        scratch.outFingerprint().get(ValueLayout.JAVA_LONG, 0));
  }

  EncodedBytes buildSampleU32Bytes(long targetBytes, EncodeScratch scratch) throws Throwable {
    scratch.resetOwnedBytes();
    int status = (int) buildSampleU32Bytes.invokeExact(
        targetBytes,
        scratch.ownedBytes(),
        scratch.outFingerprint());
    checkStatus(status, "C API u32 encode failed");

    MemorySegment data = scratch.ownedBytes().get(ValueLayout.ADDRESS, 0);
    long size = scratch.ownedBytes().get(ValueLayout.JAVA_LONG, ADDRESS_BYTES);
    if (size < 0 || size > Integer.MAX_VALUE) {
      freeBytes.invokeExact(scratch.ownedBytes());
      throw new IllegalStateException("C API encoded payload has an unsupported size.");
    }

    return new EncodedBytes(
        scratch.ownedBytes(),
        data.reinterpret(size),
        (int) size,
      0L,
      scratch.outFingerprint().get(ValueLayout.JAVA_LONG, 0));
  }

  DecodeResult processBinaryModelBytes(MemorySegment data, long size, ConsumeScratch scratch) throws Throwable {
    int status = (int) processBinaryModelBytes.invokeExact(data, size, scratch.outCount(), scratch.outFingerprint());
    checkStatus(status, "C API BinaryModel decode failed");
    return new DecodeResult(
        scratch.outCount().get(ValueLayout.JAVA_LONG, 0),
        scratch.outFingerprint().get(ValueLayout.JAVA_LONG, 0));
  }

  EncodedBytes buildSampleBinaryModelBytes(long targetBytes, EncodeScratch scratch) throws Throwable {
    scratch.resetOwnedBytes();
    int status = (int) buildSampleBinaryModelBytes.invokeExact(
        targetBytes,
        scratch.ownedBytes(),
        scratch.outCount(),
        scratch.outFingerprint());
    checkStatus(status, "C API BinaryModel encode failed");

    MemorySegment data = scratch.ownedBytes().get(ValueLayout.ADDRESS, 0);
    long size = scratch.ownedBytes().get(ValueLayout.JAVA_LONG, ADDRESS_BYTES);
    if (size < 0 || size > Integer.MAX_VALUE) {
      freeBytes.invokeExact(scratch.ownedBytes());
      throw new IllegalStateException("C API encoded payload has an unsupported size.");
    }

    return new EncodedBytes(
        scratch.ownedBytes(),
        data.reinterpret(size),
        (int) size,
        scratch.outCount().get(ValueLayout.JAVA_LONG, 0),
        scratch.outFingerprint().get(ValueLayout.JAVA_LONG, 0));
  }

  void freeBytes(EncodedBytes bytes) throws Throwable {
    freeBytes.invokeExact(bytes.ownedBytes());
  }

  private static MethodHandle downcall(
      Linker linker,
      SymbolLookup lookup,
      String name,
      FunctionDescriptor descriptor) {
    MemorySegment symbol = lookup.find(name)
        .orElseThrow(() -> new IllegalStateException("Missing C API symbol: " + name));
    return linker.downcallHandle(symbol, descriptor);
  }

  private static void checkStatus(int status, String message) {
    if (status != 0) {
      throw new IllegalStateException(message + ": status=" + status);
    }
  }

  record ConsumeScratch(MemorySegment outCount, MemorySegment outFingerprint) {
  }

  record EncodeScratch(
      MemorySegment ownedBytes,
      MemorySegment outCount,
      MemorySegment outFingerprint) {
    void resetOwnedBytes() {
      ownedBytes.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
      ownedBytes.set(ValueLayout.JAVA_LONG, ADDRESS_BYTES, 0L);
    }
  }

  record DecodeResult(long count, long fingerprint) {
    long combinedValue() {
      return fingerprint ^ count;
    }
  }

  record EncodedBytes(
      MemorySegment ownedBytes,
      MemorySegment data,
      int size,
      long count,
      long fingerprint) {
  }
}
