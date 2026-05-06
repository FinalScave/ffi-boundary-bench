export const processBinaryModelBytes: (buffer: ArrayBuffer, size: number) => bigint;
export const buildSampleBinaryModelBytes: (targetBytes: number) => ArrayBuffer;
export const buildSampleBinaryModelBytesExternal: (targetBytes: number) => ArrayBuffer;
export const processU32Bytes: (buffer: ArrayBuffer, size: number) => bigint;
export const buildSampleU32Bytes: (targetBytes: number) => ArrayBuffer;
export const buildSampleU32BytesExternal: (targetBytes: number) => ArrayBuffer;
export const allocateExternalBuffer: (size: number) => ArrayBuffer;
export const nanoTime: () => bigint;
