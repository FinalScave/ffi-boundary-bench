package com.ffibb.jvmwasm;

import java.io.IOException;
import java.nio.file.Path;

enum WasmRuntime {
  GRAALWASM("graalwasm", "GraalWasm") {
    @Override
    WasmBindings load(Path wasmPath) throws IOException {
      return GraalWasmBindings.load(wasmPath);
    }
  },
  CHICORY("chicory", "Chicory") {
    @Override
    WasmBindings load(Path wasmPath) throws IOException {
      return ChicoryWasmBindings.load(wasmPath);
    }
  };

  private final String id;
  private final String displayName;

  WasmRuntime(String id, String displayName) {
    this.id = id;
    this.displayName = displayName;
  }

  abstract WasmBindings load(Path wasmPath) throws IOException;

  String id() {
    return id;
  }

  String displayName() {
    return displayName;
  }

  static WasmRuntime parse(String value) {
    for (WasmRuntime runtime : values()) {
      if (runtime.id.equalsIgnoreCase(value) || runtime.displayName.equalsIgnoreCase(value)) {
        return runtime;
      }
    }
    throw new IllegalArgumentException("Unknown wasm runtime: " + value + ". Expected graalwasm or chicory.");
  }
}

interface WasmMemory {
  void writeBytes(int pointer, byte[] bytes);

  byte[] readBytes(int pointer, int size);

  void writeU32(int pointer, int value);

  int readU32(int pointer);

  void writeU64(int pointer, long value);

  long readU64(int pointer);
}

abstract class WasmBindings implements AutoCloseable {
  abstract WasmMemory memory();

  abstract int malloc(int size);

  abstract void free(int pointer);

  void writeBytes(int pointer, byte[] bytes) {
    memory().writeBytes(pointer, bytes);
  }

  byte[] readBytes(int pointer, int size) {
    return memory().readBytes(pointer, size);
  }

  abstract DecodeResult processU32Bytes(int pointer, int size);

  abstract DecodeResult processBinaryModelBytes(int pointer, int size);

  abstract EncodedBytes buildSampleU32Bytes(int targetBytes);

  abstract EncodedBytes buildSampleBinaryModelBytes(int targetBytes);

  abstract void freeBytes(EncodedBytes bytes);

  static void checkStatus(int status, String message) {
    if (status != 0) {
      throw new IllegalStateException(message + ": status=" + status);
    }
  }

  record DecodeResult(long count, long fingerprint) {
    long combinedValue() {
      return fingerprint ^ count;
    }
  }

  record EncodedBytes(int ownedBytesPointer, int dataPointer, int size, long count, long fingerprint) {
  }
}


