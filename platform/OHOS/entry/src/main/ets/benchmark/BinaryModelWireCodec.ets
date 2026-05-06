import util from '@ohos.util';
import { BinaryModel } from './BinaryModel';

export const RECORD_HEADER_BYTES: number = 12;

export class BinaryModelWireCodec {
  static encodedSize(models: Array<BinaryModel>): number {
    let total = 0;
    for (const m of models) {
      total += RECORD_HEADER_BYTES + m.stringValue.length;
    }
    return total;
  }

  static encode(models: Array<BinaryModel>): ArrayBuffer {
    const buffer = new ArrayBuffer(BinaryModelWireCodec.encodedSize(models));
    BinaryModelWireCodec.encodeInto(models, buffer);
    return buffer;
  }

  static encodeInto(models: Array<BinaryModel>, output: ArrayBuffer): number {
    const view: DataView = new DataView(output);
    const u8: Uint8Array = new Uint8Array(output);
    let encoder: util.TextEncoder | null = null;
    let offset = 0;

    for (const m of models) {
      view.setInt32(offset, m.intValue, true);
      offset += 4;
      view.setFloat32(offset, m.floatValue, true);
      offset += 4;

      const capacity: number = u8.byteLength - (offset + 4);
      if (capacity < 0) {
        throw new Error('BinaryModel encode buffer underrun.');
      }
      const target: Uint8Array = u8.subarray(offset + 4, offset + 4 + capacity);

      let written: number;
      if (BinaryModelWireCodec.isPlainAscii(m.stringValue)) {
        const len: number = m.stringValue.length;
        if (len > capacity) {
          throw new Error('BinaryModel UTF-8 exceeds available encode space.');
        }
        for (let i = 0; i < len; i++) {
          target[i] = m.stringValue.charCodeAt(i) & 0xff;
        }
        written = len;
      } else {
        if (encoder === null) {
          encoder = new util.TextEncoder('utf-8');
        }
        const scratchBytes: number = Math.max(1, m.stringValue.length * 4);
        const scratchBuf: ArrayBuffer = new ArrayBuffer(scratchBytes);
        const scratchU8: Uint8Array = new Uint8Array(scratchBuf);
        const result: util.EncodeIntoUint8ArrayInfo = encoder.encodeIntoUint8Array(m.stringValue, scratchU8);
        written = result.written;
        if (written > capacity) {
          throw new Error('BinaryModel UTF-8 exceeds available encode space.');
        }
        target.set(scratchU8.subarray(0, written));
      }

      view.setUint32(offset, written, true);
      offset += 4 + written;
    }
    return offset;
  }

  private static isPlainAscii(s: string): boolean {
    for (let i = 0; i < s.length; i++) {
      if (s.charCodeAt(i) >= 128) {
        return false;
      }
    }
    return true;
  }

  static decode(buffer: ArrayBuffer, size: number): Array<BinaryModel> {
    const view: DataView = new DataView(buffer, 0, size);
    const u8: Uint8Array = new Uint8Array(buffer, 0, size);
    const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
    const models: Array<BinaryModel> = [];
    let offset = 0;

    while (offset < size) {
      if (size - offset < RECORD_HEADER_BYTES) {
        throw new Error('Unexpected end of compact byte payload.');
      }
      const intValue: number = view.getInt32(offset, true);
      offset += 4;
      const floatValue: number = view.getFloat32(offset, true);
      offset += 4;
      const stringSize: number = view.getUint32(offset, true);
      offset += 4;

      if (stringSize > size - offset) {
        throw new Error('String size exceeds compact payload size.');
      }

      const stringValue: string = decoder.decodeToString(u8.subarray(offset, offset + stringSize));
      offset += stringSize;
      models.push(new BinaryModel(intValue, floatValue, stringValue));
    }
    return models;
  }

  static fingerprint(models: Array<BinaryModel>): bigint {
    if (models.length === 0) {
      return 0n;
    }

    const middle = Math.floor(models.length / 2);
    const samples: Array<BinaryModel> = [models[0], models[middle], models[models.length - 1]];

    const M1 = 1315423911n;
    const M2 = 1099511628211n;
    const MASK64 = 0xffffffffffffffffn;
    const MASK32 = 0xffffffffn;

    let value = BigInt(models.length);
    for (const m of samples) {
      value = ((value * M1) & MASK64) ^ (BigInt(m.intValue >>> 0) & MASK32);
      value = ((value * M1) & MASK64) ^ (BigInt(BinaryModelWireCodec.floatToBits(m.floatValue)) & MASK32);
      value = ((value * M1) & MASK64) ^ BigInt(m.stringValue.length);
      for (let i = 0; i < m.stringValue.length; i++) {
        value = ((value * M2) & MASK64) ^ BigInt(m.stringValue.charCodeAt(i) & 0xff);
      }
    }
    return value & MASK64;
  }

  private static floatToBits(value: number): number {
    const buffer = new ArrayBuffer(4);
    const dv = new DataView(buffer);
    dv.setFloat32(0, value, true);
    return dv.getUint32(0, true);
  }
}
