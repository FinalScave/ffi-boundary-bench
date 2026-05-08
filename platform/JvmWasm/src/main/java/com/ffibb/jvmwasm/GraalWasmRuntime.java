package com.ffibb.jvmwasm;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

final class GraalWasmBindings extends WasmBindings {
  private final Context context;
  private final Value exports;
  private final GraalWasmMemory memory;
  private final Value malloc;
  private final Value free;
  private final Value processU32Bytes;
  private final Value processBinaryModelBytes;
  private final Value buildSampleU32Bytes;
  private final Value buildSampleBinaryModelBytes;
  private final Value ownedBytesStructSize;
  private final Value ownedBytesData;
  private final Value ownedBytesSize;
  private final Value freeBytes;

  private GraalWasmBindings(Context context, Value exports) {
    this.context = context;
    this.exports = exports;
    memory = new GraalWasmMemory(member(exports, "memory"));
    malloc = member(exports, "_malloc", "malloc");
    free = member(exports, "_free", "free");
    processU32Bytes = member(exports, "_ffibb_wasm_process_u32_bytes", "ffibb_wasm_process_u32_bytes");
    processBinaryModelBytes =
        member(exports, "_ffibb_wasm_process_binary_model_bytes", "ffibb_wasm_process_binary_model_bytes");
    buildSampleU32Bytes = member(exports, "_ffibb_wasm_build_sample_u32_bytes", "ffibb_wasm_build_sample_u32_bytes");
    buildSampleBinaryModelBytes =
        member(exports, "_ffibb_wasm_build_sample_binary_model_bytes", "ffibb_wasm_build_sample_binary_model_bytes");
    ownedBytesStructSize =
        member(exports, "_ffibb_wasm_owned_bytes_struct_size", "ffibb_wasm_owned_bytes_struct_size");
    ownedBytesData = member(exports, "_ffibb_wasm_owned_bytes_data", "ffibb_wasm_owned_bytes_data");
    ownedBytesSize = member(exports, "_ffibb_wasm_owned_bytes_size", "ffibb_wasm_owned_bytes_size");
    freeBytes = member(exports, "_ffibb_wasm_free_bytes", "ffibb_wasm_free_bytes");
  }

  static GraalWasmBindings load(Path wasmPath) throws IOException {
    Context context = Context.newBuilder("wasm")
        .allowExperimentalOptions(true)
        .option("wasm.Builtins", "wasi_snapshot_preview1")
        .build();
    boolean success = false;
    try {
      Source source = Source.newBuilder("wasm", wasmPath.toFile()).build();
      Value module = context.eval(source);
      Value instance = module.newInstance();
      Value exports = instance.getMember("exports");
      if (exports == null) {
        throw new IllegalStateException("Wasm instance does not expose exports.");
      }
      executeInitializer(exports);
      success = true;
      return new GraalWasmBindings(context, exports);
    } finally {
      if (!success) {
        context.close();
      }
    }
  }

  @Override
  WasmMemory memory() {
    return memory;
  }

  @Override
  int malloc(int size) {
    int pointer = malloc.execute(size).asInt();
    if (pointer == 0) {
      throw new IllegalStateException("Wasm malloc failed for " + size + " bytes.");
    }
    return pointer;
  }

  @Override
  void free(int pointer) {
    if (pointer != 0) {
      free.execute(pointer);
    }
  }

  @Override
  DecodeResult processU32Bytes(int pointer, int size) {
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU64(fingerprintPointer, 0L);
      int status = processU32Bytes.execute(pointer, size, fingerprintPointer).asInt();
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
      int status = processBinaryModelBytes.execute(pointer, size, countPointer, fingerprintPointer).asInt();
      checkStatus(status, "Wasm C API BinaryModel decode failed");
      return new DecodeResult(memory.readU32(countPointer), memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
      free(countPointer);
    }
  }

  @Override
  EncodedBytes buildSampleU32Bytes(int targetBytes) {
    int ownedBytesPointer = malloc(ownedBytesStructSize.execute().asInt());
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU64(fingerprintPointer, 0L);
      int status = buildSampleU32Bytes.execute(targetBytes, ownedBytesPointer, fingerprintPointer).asInt();
      checkStatus(status, "Wasm C API u32 encode failed");
      return new EncodedBytes(
          ownedBytesPointer,
          ownedBytesData.execute(ownedBytesPointer).asInt(),
          ownedBytesSize.execute(ownedBytesPointer).asInt(),
          targetBytes / Integer.BYTES,
          memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
    }
  }

  @Override
  EncodedBytes buildSampleBinaryModelBytes(int targetBytes) {
    int ownedBytesPointer = malloc(ownedBytesStructSize.execute().asInt());
    int countPointer = malloc(Integer.BYTES);
    int fingerprintPointer = malloc(Long.BYTES);
    try {
      memory.writeU32(countPointer, 0);
      memory.writeU64(fingerprintPointer, 0L);
      int status = buildSampleBinaryModelBytes
          .execute(targetBytes, ownedBytesPointer, countPointer, fingerprintPointer)
          .asInt();
      checkStatus(status, "Wasm C API BinaryModel encode failed");
      return new EncodedBytes(
          ownedBytesPointer,
          ownedBytesData.execute(ownedBytesPointer).asInt(),
          ownedBytesSize.execute(ownedBytesPointer).asInt(),
          memory.readU32(countPointer),
          memory.readU64(fingerprintPointer));
    } finally {
      free(fingerprintPointer);
      free(countPointer);
    }
  }

  @Override
  void freeBytes(EncodedBytes bytes) {
    freeBytes.execute(bytes.ownedBytesPointer());
    free(bytes.ownedBytesPointer());
  }

  @Override
  public void close() {
    context.close();
  }

  private static void executeInitializer(Value exports) {
    if (exports.hasMember("_initialize")) {
      exports.getMember("_initialize").execute();
    } else if (exports.hasMember("__wasm_call_ctors")) {
      exports.getMember("__wasm_call_ctors").execute();
    }
  }

  private static Value member(Value exports, String... names) {
    for (String name : names) {
      if (exports.hasMember(name)) {
        return exports.getMember(name);
      }
    }
    throw new IllegalStateException("Missing wasm export: " + String.join(" or ", names));
  }
}


final class GraalWasmMemory implements WasmMemory {
  private final Value memory;

  GraalWasmMemory(Value memory) {
    this.memory = memory;
  }

  @Override
  public void writeBytes(int pointer, byte[] bytes) {
    long base = offset(pointer);
    for (int index = 0; index < bytes.length; index++) {
      memory.writeBufferByte(base + index, bytes[index]);
    }
  }

  @Override
  public byte[] readBytes(int pointer, int size) {
    byte[] bytes = new byte[size];
    memory.readBuffer(offset(pointer), bytes, 0, size);
    return bytes;
  }

  @Override
  public void writeU32(int pointer, int value) {
    memory.writeBufferInt(ByteOrder.LITTLE_ENDIAN, offset(pointer), value);
  }

  @Override
  public int readU32(int pointer) {
    return memory.readBufferInt(ByteOrder.LITTLE_ENDIAN, offset(pointer));
  }

  @Override
  public void writeU64(int pointer, long value) {
    memory.writeBufferLong(ByteOrder.LITTLE_ENDIAN, offset(pointer), value);
  }

  @Override
  public long readU64(int pointer) {
    return memory.readBufferLong(ByteOrder.LITTLE_ENDIAN, offset(pointer));
  }

  private static long offset(int pointer) {
    return Integer.toUnsignedLong(pointer);
  }
}


