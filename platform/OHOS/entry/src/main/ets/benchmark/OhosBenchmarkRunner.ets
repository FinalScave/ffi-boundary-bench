import { BinaryModel } from './BinaryModel';
import { BinaryModelPayloadFactory } from './BinaryModelPayloadFactory';
import { BinaryModelWireCodec } from './BinaryModelWireCodec';
import { U32PayloadCodec } from './U32PayloadCodec';
import { BenchmarkBridge } from './BenchmarkBridge';
import { BenchmarkCase } from './BenchmarkCase';
import {
  BenchmarkRow,
  PAYLOAD_FAMILY_BINARY_MODEL,
  PAYLOAD_FAMILY_U32,
} from './BenchmarkRow';
import { BenchmarkSink } from './BenchmarkSink';
import { BenchmarkTimer } from './BenchmarkTimer';
import { BenchmarkReportFormatter } from './BenchmarkReportFormatter';

const BINDING_ARRAY_BUFFER: string = 'array_buffer';
const BINDING_EXTERNAL_ARRAY_BUFFER: string = 'external_array_buffer';
const OP_OHOS_ENCODE: string = 'ohos_encode_to_c_api_decode';
const OP_C_API_ENCODE: string = 'c_api_encode_to_ohos_decode';
const WARMUP_ROUNDS: number = 3;

const CASES: Array<BenchmarkCase> = [
  new BenchmarkCase('10 KiB', 10 * 1024, 100),
  new BenchmarkCase('200 KiB', 200 * 1024, 30),
  new BenchmarkCase('1 MiB', 1024 * 1024, 10),
];

export class OhosBenchmarkRunner {
  static runAll(): string {
    BenchmarkSink.reset();

    const rows: Array<BenchmarkRow> = [];
    const modelCounts: Array<number> = [];
    const u32ElementCounts: Array<number> = [];

    for (const c of CASES) {
      const source: Array<BinaryModel> = BinaryModelPayloadFactory.createModelsForTargetBytes(c.targetBytes);
      modelCounts.push(source.length);

      const u32Source: Uint32Array = U32PayloadCodec.createValuesForTargetBytes(c.targetBytes);
      u32ElementCounts.push(u32Source.length);

      rows.push(OhosBenchmarkRunner.runBinaryModelEncodeArrayBuffer(c, source));
      rows.push(OhosBenchmarkRunner.runBinaryModelDecodeArrayBuffer(c));
      rows.push(OhosBenchmarkRunner.runBinaryModelEncodeExternal(c, source));
      rows.push(OhosBenchmarkRunner.runBinaryModelDecodeExternal(c));

      rows.push(OhosBenchmarkRunner.runU32EncodeArrayBuffer(c, u32Source));
      rows.push(OhosBenchmarkRunner.runU32DecodeArrayBuffer(c));
      rows.push(OhosBenchmarkRunner.runU32EncodeExternal(c, u32Source));
      rows.push(OhosBenchmarkRunner.runU32DecodeExternal(c));
    }

    return BenchmarkReportFormatter.format(CASES, modelCounts, u32ElementCounts, rows);
  }

  private static runBinaryModelEncodeArrayBuffer(c: BenchmarkCase, source: Array<BinaryModel>): BenchmarkRow {
    const warmup: ArrayBuffer = BinaryModelWireCodec.encode(source);
    BenchmarkSink.add(BenchmarkBridge.processBinaryModelBytes(warmup, warmup.byteLength));

    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const buf: ArrayBuffer = BinaryModelWireCodec.encode(source);
      return BenchmarkBridge.processBinaryModelBytes(buf, buf.byteLength);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
      BINDING_ARRAY_BUFFER,
      OP_OHOS_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      source.length,
      measurement,
    );
  }

  private static runBinaryModelDecodeArrayBuffer(c: BenchmarkCase): BenchmarkRow {
    const warmup: ArrayBuffer = BenchmarkBridge.buildSampleBinaryModelBytes(c.targetBytes);
    const warmupModels: Array<BinaryModel> = BinaryModelWireCodec.decode(warmup, warmup.byteLength);
    BenchmarkSink.add(BinaryModelWireCodec.fingerprint(warmupModels));

    let lastCount: number = warmupModels.length;
    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const buf: ArrayBuffer = BenchmarkBridge.buildSampleBinaryModelBytes(c.targetBytes);
      const models: Array<BinaryModel> = BinaryModelWireCodec.decode(buf, buf.byteLength);
      lastCount = models.length;
      return BinaryModelWireCodec.fingerprint(models);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
      BINDING_ARRAY_BUFFER,
      OP_C_API_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      lastCount,
      measurement,
    );
  }

  private static runBinaryModelEncodeExternal(c: BenchmarkCase, source: Array<BinaryModel>): BenchmarkRow {
    const buf: ArrayBuffer = BenchmarkBridge.allocateExternalBuffer(c.targetBytes);
    const warmupSize: number = BinaryModelWireCodec.encodeInto(source, buf);
    BenchmarkSink.add(BenchmarkBridge.processBinaryModelBytes(buf, warmupSize));

    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const sz: number = BinaryModelWireCodec.encodeInto(source, buf);
      return BenchmarkBridge.processBinaryModelBytes(buf, sz);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
      BINDING_EXTERNAL_ARRAY_BUFFER,
      OP_OHOS_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      source.length,
      measurement,
    );
  }

  private static runBinaryModelDecodeExternal(c: BenchmarkCase): BenchmarkRow {
    const warmup: ArrayBuffer = BenchmarkBridge.buildSampleBinaryModelBytesExternal(c.targetBytes);
    const warmupModels: Array<BinaryModel> = BinaryModelWireCodec.decode(warmup, warmup.byteLength);
    BenchmarkSink.add(BinaryModelWireCodec.fingerprint(warmupModels));

    let lastCount: number = warmupModels.length;
    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const ext: ArrayBuffer = BenchmarkBridge.buildSampleBinaryModelBytesExternal(c.targetBytes);
      const models: Array<BinaryModel> = BinaryModelWireCodec.decode(ext, ext.byteLength);
      lastCount = models.length;
      return BinaryModelWireCodec.fingerprint(models);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_BINARY_MODEL,
      BINDING_EXTERNAL_ARRAY_BUFFER,
      OP_C_API_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      lastCount,
      measurement,
    );
  }

  private static runU32EncodeArrayBuffer(c: BenchmarkCase, source: Uint32Array): BenchmarkRow {
    const warmup: ArrayBuffer = U32PayloadCodec.encode(source);
    BenchmarkSink.add(BenchmarkBridge.processU32Bytes(warmup, warmup.byteLength));

    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const buf: ArrayBuffer = U32PayloadCodec.encode(source);
      return BenchmarkBridge.processU32Bytes(buf, buf.byteLength);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_U32,
      BINDING_ARRAY_BUFFER,
      OP_OHOS_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      source.length,
      measurement,
    );
  }

  private static runU32DecodeArrayBuffer(c: BenchmarkCase): BenchmarkRow {
    const warmup: ArrayBuffer = BenchmarkBridge.buildSampleU32Bytes(c.targetBytes);
    const warmupValues: Uint32Array = U32PayloadCodec.decode(warmup, warmup.byteLength);
    BenchmarkSink.add(U32PayloadCodec.fingerprint(warmupValues));

    let lastCount: number = warmupValues.length;
    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const buf: ArrayBuffer = BenchmarkBridge.buildSampleU32Bytes(c.targetBytes);
      const values: Uint32Array = U32PayloadCodec.decode(buf, buf.byteLength);
      lastCount = values.length;
      return U32PayloadCodec.fingerprint(values);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_U32,
      BINDING_ARRAY_BUFFER,
      OP_C_API_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      lastCount,
      measurement,
    );
  }

  private static runU32EncodeExternal(c: BenchmarkCase, source: Uint32Array): BenchmarkRow {
    const buf: ArrayBuffer = BenchmarkBridge.allocateExternalBuffer(c.targetBytes);
    const warmupSize: number = U32PayloadCodec.encodeInto(source, buf);
    BenchmarkSink.add(BenchmarkBridge.processU32Bytes(buf, warmupSize));

    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const sz: number = U32PayloadCodec.encodeInto(source, buf);
      return BenchmarkBridge.processU32Bytes(buf, sz);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_U32,
      BINDING_EXTERNAL_ARRAY_BUFFER,
      OP_OHOS_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      source.length,
      measurement,
    );
  }

  private static runU32DecodeExternal(c: BenchmarkCase): BenchmarkRow {
    const warmup: ArrayBuffer = BenchmarkBridge.buildSampleU32BytesExternal(c.targetBytes);
    const warmupValues: Uint32Array = U32PayloadCodec.decode(warmup, warmup.byteLength);
    BenchmarkSink.add(U32PayloadCodec.fingerprint(warmupValues));

    let lastCount: number = warmupValues.length;
    const measurement = BenchmarkTimer.measure(c.iterations, WARMUP_ROUNDS, () => {
      const ext: ArrayBuffer = BenchmarkBridge.buildSampleU32BytesExternal(c.targetBytes);
      const values: Uint32Array = U32PayloadCodec.decode(ext, ext.byteLength);
      lastCount = values.length;
      return U32PayloadCodec.fingerprint(values);
    });

    return new BenchmarkRow(
      PAYLOAD_FAMILY_U32,
      BINDING_EXTERNAL_ARRAY_BUFFER,
      OP_C_API_ENCODE,
      c.name,
      c.targetBytes,
      c.iterations,
      lastCount,
      measurement,
    );
  }
}
