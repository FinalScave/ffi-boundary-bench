import ffibb from 'libffibb.so';

export class BenchmarkBridge {
  static processBinaryModelBytes(buffer: ArrayBuffer, size: number): bigint {
    return ffibb.processBinaryModelBytes(buffer, size);
  }

  static buildSampleBinaryModelBytes(targetBytes: number): ArrayBuffer {
    return ffibb.buildSampleBinaryModelBytes(targetBytes);
  }

  static buildSampleBinaryModelBytesExternal(targetBytes: number): ArrayBuffer {
    return ffibb.buildSampleBinaryModelBytesExternal(targetBytes);
  }

  static processU32Bytes(buffer: ArrayBuffer, size: number): bigint {
    return ffibb.processU32Bytes(buffer, size);
  }

  static buildSampleU32Bytes(targetBytes: number): ArrayBuffer {
    return ffibb.buildSampleU32Bytes(targetBytes);
  }

  static buildSampleU32BytesExternal(targetBytes: number): ArrayBuffer {
    return ffibb.buildSampleU32BytesExternal(targetBytes);
  }

  static allocateExternalBuffer(size: number): ArrayBuffer {
    return ffibb.allocateExternalBuffer(size);
  }

  static nanoTime(): bigint {
    return ffibb.nanoTime();
  }
}
