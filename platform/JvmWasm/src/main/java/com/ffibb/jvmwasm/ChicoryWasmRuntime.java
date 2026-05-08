package com.ffibb.jvmwasm;

import java.io.IOException;
import java.nio.file.Path;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

final class ChicoryWasmBindings extends WasmBindings {
  private final ChicoryWasmMemory memory;
  private final ExportFunction malloc;
  private final ExportFunction free;
  private final ExportFunction processU32Bytes;
  private final ExportFunction processBinaryModelBytes;
  private final ExportFunction buildSampleU32Bytes;
  private final ExportFunction buildSampleBinaryModelBytes;
  private final ExportFunction ownedBytesData;
  private final ExportFunction ownedBytesSize;
  private final ExportFunction freeBytes;
  private final int ownedBytesStructSize;

  private ChicoryWasmBindings(Instance instance) {
    memory = new ChicoryWasmMemory(instance.memory());
    malloc = function(instance, "_malloc", "malloc");
    free = function(instance, "_free", "free");
    processU32Bytes = function(instance, "_ffibb_wasm_process_u32_bytes", "ffibb_wasm_process_u32_bytes");
    processBinaryModelBytes =
        function(instance, "_ffibb_wasm_process_binary_model_bytes", "ffibb_wasm_process_binary_model_bytes");
    buildSampleU32Bytes = function(instance, "_ffibb_wasm_build_sample_u32_bytes", "ffibb_wasm_build_sample_u32_bytes");
    buildSampleBinaryModelBytes =
        function(instance, "_ffibb_wasm_build_sample_binary_model_bytes", "ffibb_wasm_build_sample_binary_model_bytes");
    ExportFunction ownedBytesStructSizeFunction =
        function(instance, "_ffibb_wasm_owned_bytes_struct_size", "ffibb_wasm_owned_bytes_struct_size");
    ownedBytesData = function(instance, "_ffibb_wasm_owned_bytes_data", "ffibb_wasm_owned_bytes_data");
    ownedBytesSize = function(instance, "_ffibb_wasm_owned_bytes_size", "ffibb_wasm_owned_bytes_size");
    freeBytes = function(instance, "_ffibb_wasm_free_bytes", "ffibb_wasm_free_bytes");
    ownedBytesStructSize = (int) ownedBytesStructSizeFunction.apply()[0];
  }

  static ChicoryWasmBindings load(Path wasmPath) throws IOException {
    WasmModule module = Parser.parse(wasmPath.toFile());
    Instance instance = Instance.builder(module).build();
    executeInitializer(instance);
    return new ChicoryWasmBindings(instance);
  }

  @Override
  WasmMemory memory() {
    return memory;
  }

  @Override
  int malloc(int size) {
    int pointer = (int) malloc.apply(size)[0];
    if (pointer == 0) {
      throw new IllegalStateException("Wasm malloc failed for " + size + " bytes.");
    }
    return pointer;
  }

  @Override
  void free(int pointer) {
    if (pointer != 0) {
      free.apply(pointer);
    }
  }

  @Override
  DecodeResult processU32Bytes(int pointer, int size) {
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU64(fingerprintPointer, 0L);
      int status = (int) processU32Bytes.apply(pointer, size, fingerprintPointer)[0];
      checkStatus(status, "Wasm C API u32 decode failed");
      return new DecodeResult(size / Integer.BYTES, memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
    }
  }

  @Override
  DecodeResult processBinaryModelBytes(int pointer, int size) {
    int countPointer = malloc(Integer.BYTES);
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU32(countPointer, 0);
      memory.writeU64(fingerprintPointer, 0L);
      int status = (int) processBinaryModelBytes.apply(pointer, size, countPointer, fingerprintPointer)[0];
      checkStatus(status, "Wasm C API BinaryModel decode failed");
      return new DecodeResult(memory.readU32(countPointer), memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
      free(countPointer);
    }
  }

  @Override
  EncodedBytes buildSampleU32Bytes(int targetBytes) {
    int ownedBytesPointer = malloc(ownedBytesStructSize);
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU64(fingerprintPointer, 0L);
      int status = (int) buildSampleU32Bytes.apply(targetBytes, ownedBytesPointer, fingerprintPointer)[0];
      checkStatus(status, "Wasm C API u32 encode failed");
      return new EncodedBytes(
          ownedBytesPointer,
          (int) ownedBytesData.apply(ownedBytesPointer)[0],
          (int) ownedBytesSize.apply(ownedBytesPointer)[0],
          targetBytes / Integer.BYTES,
          memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
    }
  }

  @Override
  EncodedBytes buildSampleBinaryModelBytes(int targetBytes) {
    int ownedBytesPointer = malloc(ownedBytesStructSize);
    int countPointer = malloc(Integer.BYTES);
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU32(countPointer, 0);
      memory.writeU64(fingerprintPointer, 0L);
      int status = (int) buildSampleBinaryModelBytes
          .apply(targetBytes, ownedBytesPointer, countPointer, fingerprintPointer)[0];
      checkStatus(status, "Wasm C API BinaryModel encode failed");
      return new EncodedBytes(
          ownedBytesPointer,
          (int) ownedBytesData.apply(ownedBytesPointer)[0],
          (int) ownedBytesSize.apply(ownedBytesPointer)[0],
          memory.readU32(countPointer),
          memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
      free(countPointer);
    }
  }

  @Override
  void freeBytes(EncodedBytes bytes) {
    freeBytes.apply(bytes.ownedBytesPointer());
    free(bytes.ownedBytesPointer());
  }

  @Override
  public void close() {
  }

  private static void executeInitializer(Instance instance) {
    if (hasFunction(instance, "_initialize")) {
      instance.export("_initialize").apply();
    } else if (hasFunction(instance, "__wasm_call_ctors")) {
      instance.export("__wasm_call_ctors").apply();
    }
  }

  private static ExportFunction function(Instance instance, String... names) {
    for (String name : names) {
      if (hasFunction(instance, name)) {
        return instance.export(name);
      }
    }
    throw new IllegalStateException("Missing wasm export: " + String.join(" or ", names));
  }

  private static boolean hasFunction(Instance instance, String name) {
    try {
      instance.export(name);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }
}


final class ChicoryWasmMemory implements WasmMemory {
  private final Memory memory;

  ChicoryWasmMemory(Memory memory) {
    this.memory = memory;
  }

  @Override
  public void writeBytes(int pointer, byte[] bytes) {
    memory.write(pointer, bytes);
  }

  @Override
  public byte[] readBytes(int pointer, int size) {
    return memory.readBytes(pointer, size);
  }

  @Override
  public void writeU32(int pointer, int value) {
    memory.writeI32(pointer, value);
  }

  @Override
  public int readU32(int pointer) {
    return memory.readInt(pointer);
  }

  @Override
  public void writeU64(int pointer, long value) {
    memory.writeLong(pointer, value);
  }

  @Override
  public long readU64(int pointer) {
    return memory.readLong(pointer);
  }
}


