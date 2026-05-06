const { CASES, elementCountForBytes } = require("./benchmark_case");
const { formatReport, jsonRows } = require("./benchmark_report_formatter");
const { measure } = require("./benchmark_timer");
const binaryModelCodec = require("./binary_model_wire_codec");
const u32Codec = require("./u32_payload_codec");

const BINDING_UINT8_ARRAY = "uint8_array";
const BINDING_WASM_HEAP = "wasm_heap";
const JS_ENCODE_TO_C_API_DECODE = "js_encode_to_c_api_decode";
const C_API_ENCODE_TO_JS_DECODE = "c_api_encode_to_js_decode";

function consume(state, value) {
  state.sink = BigInt.asUintN(64, state.sink + BigInt(value));
}

function verify(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function makeRow(payloadFamily, binding, operation, benchmarkCase, elementCount, measurement) {
  return {
    payloadFamily,
    binding,
    operation,
    case: benchmarkCase,
    elementCount,
    measurement,
  };
}

function runBinaryModelJsToCApiUint8Array(bridge, benchmarkCase, models, state) {
  const expectedFingerprint = binaryModelCodec.fingerprint(models);
  const expectedCount = models.length;
  const pointer = bridge.malloc(benchmarkCase.targetBytes);
  try {
    const measurement = measure(benchmarkCase.iterations, () => {
      const bytes = binaryModelCodec.encode(models);
      bridge.copyBytesToHeap(bytes, pointer);
      const result = bridge.processBinaryModelBytes(pointer, bytes.byteLength);
      verify(result.count === expectedCount && result.fingerprint === expectedFingerprint, "BinaryModel C API decode produced an unexpected fingerprint.");
      consume(state, result.fingerprint + BigInt(result.count));
    });

    return makeRow("BinaryModel", BINDING_UINT8_ARRAY, JS_ENCODE_TO_C_API_DECODE, benchmarkCase, expectedCount, measurement);
  } finally {
    bridge.free(pointer);
  }
}

function runBinaryModelCApiToJsUint8Array(bridge, benchmarkCase, models, state) {
  const expectedFingerprint = binaryModelCodec.fingerprint(models);
  let latestCount = models.length;
  const measurement = measure(benchmarkCase.iterations, () => {
    bridge.withSampleBinaryModelBytes(benchmarkCase.targetBytes, (encoded) => {
      const bytes = new Uint8Array(bridge.bytesView(encoded.dataPointer, encoded.size));
      const decodedModels = binaryModelCodec.decode(bytes);
      const fingerprint = binaryModelCodec.fingerprint(decodedModels);
      verify(encoded.count === decodedModels.length && encoded.fingerprint === fingerprint, "JS BinaryModel decode produced an unexpected fingerprint.");
      verify(fingerprint === expectedFingerprint, "JS BinaryModel decode did not match the source payload.");
      latestCount = decodedModels.length;
      consume(state, fingerprint + BigInt(latestCount));
    });
  });

  return makeRow("BinaryModel", BINDING_UINT8_ARRAY, C_API_ENCODE_TO_JS_DECODE, benchmarkCase, latestCount, measurement);
}

function runBinaryModelJsToCApiWasmHeap(bridge, benchmarkCase, models, state) {
  const expectedFingerprint = binaryModelCodec.fingerprint(models);
  const expectedCount = models.length;
  const pointer = bridge.malloc(benchmarkCase.targetBytes);
  try {
    const measurement = measure(benchmarkCase.iterations, () => {
      binaryModelCodec.encodeToHeap(models, bridge.module, pointer);
      const result = bridge.processBinaryModelBytes(pointer, benchmarkCase.targetBytes);
      verify(result.count === expectedCount && result.fingerprint === expectedFingerprint, "BinaryModel C API decode produced an unexpected fingerprint.");
      consume(state, result.fingerprint + BigInt(result.count));
    });

    return makeRow("BinaryModel", BINDING_WASM_HEAP, JS_ENCODE_TO_C_API_DECODE, benchmarkCase, expectedCount, measurement);
  } finally {
    bridge.free(pointer);
  }
}

function runBinaryModelCApiToJsWasmHeap(bridge, benchmarkCase, models, state) {
  const expectedFingerprint = binaryModelCodec.fingerprint(models);
  let latestCount = models.length;
  const measurement = measure(benchmarkCase.iterations, () => {
    bridge.withSampleBinaryModelBytes(benchmarkCase.targetBytes, (encoded) => {
      const decodedModels = binaryModelCodec.decode(bridge.bytesView(encoded.dataPointer, encoded.size));
      const fingerprint = binaryModelCodec.fingerprint(decodedModels);
      verify(encoded.count === decodedModels.length && encoded.fingerprint === fingerprint, "JS BinaryModel decode produced an unexpected fingerprint.");
      verify(fingerprint === expectedFingerprint, "JS BinaryModel decode did not match the source payload.");
      latestCount = decodedModels.length;
      consume(state, fingerprint + BigInt(latestCount));
    });
  });

  return makeRow("BinaryModel", BINDING_WASM_HEAP, C_API_ENCODE_TO_JS_DECODE, benchmarkCase, latestCount, measurement);
}

function runU32JsToCApiUint8Array(bridge, benchmarkCase, values, state) {
  const expectedFingerprint = u32Codec.fingerprint(values);
  const pointer = bridge.malloc(benchmarkCase.targetBytes);
  try {
    const measurement = measure(benchmarkCase.iterations, () => {
      const bytes = u32Codec.encode(values);
      bridge.copyBytesToHeap(bytes, pointer);
      const fingerprint = bridge.processU32Bytes(pointer, bytes.byteLength);
      verify(fingerprint === expectedFingerprint, "u32 C API decode produced an unexpected fingerprint.");
      consume(state, fingerprint + BigInt(values.length));
    });

    return makeRow("u32", BINDING_UINT8_ARRAY, JS_ENCODE_TO_C_API_DECODE, benchmarkCase, values.length, measurement);
  } finally {
    bridge.free(pointer);
  }
}

function runU32CApiToJsUint8Array(bridge, benchmarkCase, values, state) {
  const expectedFingerprint = u32Codec.fingerprint(values);
  const measurement = measure(benchmarkCase.iterations, () => {
    bridge.withSampleU32Bytes(benchmarkCase.targetBytes, (encoded) => {
      const bytes = new Uint8Array(bridge.bytesView(encoded.dataPointer, encoded.size));
      const decodedValues = u32Codec.decodeCopied(bytes);
      const fingerprint = u32Codec.fingerprint(decodedValues);
      verify(fingerprint === encoded.fingerprint && fingerprint === expectedFingerprint, "JS u32 decode produced an unexpected fingerprint.");
      consume(state, fingerprint + BigInt(decodedValues.length));
    });
  });

  return makeRow("u32", BINDING_UINT8_ARRAY, C_API_ENCODE_TO_JS_DECODE, benchmarkCase, values.length, measurement);
}

function runU32JsToCApiWasmHeap(bridge, benchmarkCase, values, state) {
  const expectedFingerprint = u32Codec.fingerprint(values);
  const pointer = bridge.malloc(benchmarkCase.targetBytes);
  try {
    const measurement = measure(benchmarkCase.iterations, () => {
      u32Codec.encodeToHeap(values, bridge.module, pointer);
      const fingerprint = bridge.processU32Bytes(pointer, benchmarkCase.targetBytes);
      verify(fingerprint === expectedFingerprint, "u32 C API decode produced an unexpected fingerprint.");
      consume(state, fingerprint + BigInt(values.length));
    });

    return makeRow("u32", BINDING_WASM_HEAP, JS_ENCODE_TO_C_API_DECODE, benchmarkCase, values.length, measurement);
  } finally {
    bridge.free(pointer);
  }
}

function runU32CApiToJsWasmHeap(bridge, benchmarkCase, values, state) {
  const expectedFingerprint = u32Codec.fingerprint(values);
  const measurement = measure(benchmarkCase.iterations, () => {
    bridge.withSampleU32Bytes(benchmarkCase.targetBytes, (encoded) => {
      const decodedValues = u32Codec.decodeHeapView(bridge.module, encoded.dataPointer, encoded.size);
      const fingerprint = u32Codec.fingerprint(decodedValues);
      verify(fingerprint === encoded.fingerprint && fingerprint === expectedFingerprint, "JS u32 decode produced an unexpected fingerprint.");
      consume(state, fingerprint + BigInt(decodedValues.length));
    });
  });

  return makeRow("u32", BINDING_WASM_HEAP, C_API_ENCODE_TO_JS_DECODE, benchmarkCase, values.length, measurement);
}

function runBenchmark(bridge, modulePath) {
  const rows = [];
  const modelCounts = [];
  const u32ElementCounts = [];
  const state = { sink: 0n };

  for (const benchmarkCase of CASES) {
    const models = binaryModelCodec.createModelsForTargetBytes(benchmarkCase.targetBytes);
    const values = u32Codec.createValuesForTargetBytes(benchmarkCase.targetBytes);

    modelCounts.push(models.length);
    u32ElementCounts.push(elementCountForBytes(benchmarkCase.targetBytes));

    rows.push(runBinaryModelJsToCApiUint8Array(bridge, benchmarkCase, models, state));
    rows.push(runBinaryModelCApiToJsUint8Array(bridge, benchmarkCase, models, state));
    rows.push(runBinaryModelJsToCApiWasmHeap(bridge, benchmarkCase, models, state));
    rows.push(runBinaryModelCApiToJsWasmHeap(bridge, benchmarkCase, models, state));
    rows.push(runU32JsToCApiUint8Array(bridge, benchmarkCase, values, state));
    rows.push(runU32CApiToJsUint8Array(bridge, benchmarkCase, values, state));
    rows.push(runU32JsToCApiWasmHeap(bridge, benchmarkCase, values, state));
    rows.push(runU32CApiToJsWasmHeap(bridge, benchmarkCase, values, state));
  }

  return {
    report: formatReport({
      modulePath,
      cases: CASES,
      modelCounts,
      u32ElementCounts,
      rows,
      sink: state.sink,
    }),
    json: jsonRows(rows),
  };
}

module.exports = {
  runBenchmark,
};
