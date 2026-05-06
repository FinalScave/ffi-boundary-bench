const fs = require("node:fs");
const path = require("node:path");

const STATUS_NAMES = [
  "OK",
  "INVALID_ARGUMENT",
  "ALLOCATION_FAILED",
  "DECODE_ERROR",
];

function repositoryRoot() {
  return path.resolve(__dirname, "..", "..", "..");
}

function candidateModulePaths() {
  const root = repositoryRoot();
  const paths = [];
  if (process.env.FFIBB_WASM_MODULE) {
    paths.push(process.env.FFIBB_WASM_MODULE);
  }
  paths.push(
    path.join(root, "build", "emscripten", "bin", "ffibb_wasm_bench.js"),
    path.join(root, "cmake-build-emscripten", "bin", "ffibb_wasm_bench.js"),
    path.join(root, "platform", "Emscripten", "dist", "ffibb_wasm_bench.js"),
  );
  return paths;
}

function resolveModulePath(explicitPath) {
  if (explicitPath) {
    const resolved = path.resolve(explicitPath);
    if (!fs.existsSync(resolved)) {
      throw new Error(`Wasm module was not found: ${resolved}`);
    }
    return resolved;
  }

  for (const candidate of candidateModulePaths()) {
    const resolved = path.resolve(candidate);
    if (fs.existsSync(resolved)) {
      return resolved;
    }
  }

  throw new Error("Wasm module was not found. Pass --module <path> or set FFIBB_WASM_MODULE.");
}

async function loadWasmModule(modulePath) {
  const resolvedModulePath = resolveModulePath(modulePath);
  const factory = require(resolvedModulePath);
  const moduleDirectory = path.dirname(resolvedModulePath);
  const module = await factory({
    locateFile(fileName) {
      return path.join(moduleDirectory, fileName);
    },
  });

  return {
    module,
    modulePath: resolvedModulePath,
  };
}

class WasmBridge {
  constructor(module) {
    this.module = module;
    this.ownedBytesStructSize = module._ffibb_wasm_owned_bytes_struct_size();
  }

  malloc(size) {
    const pointer = this.module._malloc(size);
    if (pointer === 0) {
      throw new Error(`wasm malloc failed for ${size} bytes.`);
    }
    return pointer;
  }

  free(pointer) {
    if (pointer !== 0) {
      this.module._free(pointer);
    }
  }

  copyBytesToHeap(bytes, pointer) {
    this.module.HEAPU8.set(bytes, pointer);
  }

  processU32Bytes(pointer, size) {
    const fingerprintPointer = this.malloc(8);
    try {
      this.writeU64(fingerprintPointer, 0n);
      const status = this.module._ffibb_wasm_process_u32_bytes(pointer, size, fingerprintPointer);
      this.checkStatus(status, "ffibb_wasm_process_u32_bytes");
      return this.readU64(fingerprintPointer);
    } finally {
      this.free(fingerprintPointer);
    }
  }

  processBinaryModelBytes(pointer, size) {
    const countPointer = this.malloc(4);
    const fingerprintPointer = this.malloc(8);
    try {
      this.module.HEAPU32[countPointer >>> 2] = 0;
      this.writeU64(fingerprintPointer, 0n);
      const status = this.module._ffibb_wasm_process_binary_model_bytes(
        pointer,
        size,
        countPointer,
        fingerprintPointer,
      );
      this.checkStatus(status, "ffibb_wasm_process_binary_model_bytes");
      return {
        count: this.module.HEAPU32[countPointer >>> 2],
        fingerprint: this.readU64(fingerprintPointer),
      };
    } finally {
      this.free(fingerprintPointer);
      this.free(countPointer);
    }
  }

  withSampleU32Bytes(targetBytes, callback) {
    const ownedBytesPointer = this.malloc(this.ownedBytesStructSize);
    const fingerprintPointer = this.malloc(8);
    try {
      this.clearOwnedBytes(ownedBytesPointer);
      this.writeU64(fingerprintPointer, 0n);
      const status = this.module._ffibb_wasm_build_sample_u32_bytes(
        targetBytes,
        ownedBytesPointer,
        fingerprintPointer,
      );
      this.checkStatus(status, "ffibb_wasm_build_sample_u32_bytes");
      return callback({
        dataPointer: this.module._ffibb_wasm_owned_bytes_data(ownedBytesPointer),
        size: this.module._ffibb_wasm_owned_bytes_size(ownedBytesPointer),
        count: targetBytes / 4,
        fingerprint: this.readU64(fingerprintPointer),
      });
    } finally {
      this.module._ffibb_wasm_free_bytes(ownedBytesPointer);
      this.free(fingerprintPointer);
      this.free(ownedBytesPointer);
    }
  }

  withSampleBinaryModelBytes(targetBytes, callback) {
    const ownedBytesPointer = this.malloc(this.ownedBytesStructSize);
    const countPointer = this.malloc(4);
    const fingerprintPointer = this.malloc(8);
    try {
      this.clearOwnedBytes(ownedBytesPointer);
      this.module.HEAPU32[countPointer >>> 2] = 0;
      this.writeU64(fingerprintPointer, 0n);
      const status = this.module._ffibb_wasm_build_sample_binary_model_bytes(
        targetBytes,
        ownedBytesPointer,
        countPointer,
        fingerprintPointer,
      );
      this.checkStatus(status, "ffibb_wasm_build_sample_binary_model_bytes");
      return callback({
        dataPointer: this.module._ffibb_wasm_owned_bytes_data(ownedBytesPointer),
        size: this.module._ffibb_wasm_owned_bytes_size(ownedBytesPointer),
        count: this.module.HEAPU32[countPointer >>> 2],
        fingerprint: this.readU64(fingerprintPointer),
      });
    } finally {
      this.module._ffibb_wasm_free_bytes(ownedBytesPointer);
      this.free(fingerprintPointer);
      this.free(countPointer);
      this.free(ownedBytesPointer);
    }
  }

  bytesView(pointer, size) {
    return this.module.HEAPU8.subarray(pointer, pointer + size);
  }

  readU64(pointer) {
    const index = pointer >>> 2;
    const low = BigInt(this.module.HEAPU32[index]);
    const high = BigInt(this.module.HEAPU32[index + 1]);
    return (high << 32n) | low;
  }

  writeU64(pointer, value) {
    const index = pointer >>> 2;
    this.module.HEAPU32[index] = Number(value & 0xffffffffn);
    this.module.HEAPU32[index + 1] = Number((value >> 32n) & 0xffffffffn);
  }

  clearOwnedBytes(pointer) {
    this.module.HEAPU8.fill(0, pointer, pointer + this.ownedBytesStructSize);
  }

  checkStatus(status, operation) {
    if (status !== 0) {
      throw new Error(`${operation} failed: ${STATUS_NAMES[status] ?? `status ${status}`}`);
    }
  }
}

module.exports = {
  WasmBridge,
  loadWasmModule,
};
