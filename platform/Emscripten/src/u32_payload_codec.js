const { elementCountForBytes } = require("./benchmark_case");

function patternValue(index) {
  const mixed = Math.imul(index >>> 0, 2654435761) ^ ((0x9e3779b9 + Math.imul(index, 17)) >>> 0);
  return mixed >>> 0;
}

function createValuesForTargetBytes(targetBytes) {
  const count = elementCountForBytes(targetBytes);
  const values = new Uint32Array(count);
  for (let index = 0; index < count; index += 1) {
    values[index] = patternValue(index);
  }
  return values;
}

function fingerprint(values) {
  if (values.length === 0) {
    return 0n;
  }

  const middle = Math.floor(values.length / 2);
  const wrapped = (values.length + values[0] + values[middle] + values[values.length - 1]) >>> 0;
  return BigInt(wrapped);
}

function encode(values) {
  const bytes = new Uint8Array(values.byteLength);
  new Uint32Array(bytes.buffer).set(values);
  return bytes;
}

function encodeToHeap(values, module, pointer) {
  new Uint32Array(module.HEAPU8.buffer, pointer, values.length).set(values);
}

function decodeCopied(bytes) {
  const view = new Uint32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4);
  return new Uint32Array(view);
}

function decodeHeapView(module, pointer, byteLength) {
  return new Uint32Array(module.HEAPU8.buffer, pointer, byteLength / 4);
}

module.exports = {
  createValuesForTargetBytes,
  decodeCopied,
  decodeHeapView,
  encode,
  encodeToHeap,
  fingerprint,
};
