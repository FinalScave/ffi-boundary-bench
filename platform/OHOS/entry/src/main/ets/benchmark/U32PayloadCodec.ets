export class U32PayloadCodec {
  static createValuesForTargetBytes(targetBytes: number): Uint32Array {
    if (targetBytes < 0 || (targetBytes % 4) !== 0) {
      throw new Error('Byte count must align with uint32 elements.');
    }

    const count: number = targetBytes / 4;
    const values: Uint32Array = new Uint32Array(count);
    for (let i = 0; i < count; i++) {
      const lhs: number = (i * 2654435761) >>> 0;
      const rhs: number = (0x9E3779B9 + i * 17) >>> 0;
      values[i] = (lhs ^ rhs) >>> 0;
    }
    return values;
  }

  static encodedSize(values: Uint32Array): number {
    return values.length * 4;
  }

  static encode(values: Uint32Array): ArrayBuffer {
    const buffer: ArrayBuffer = new ArrayBuffer(U32PayloadCodec.encodedSize(values));
    U32PayloadCodec.encodeInto(values, buffer);
    return buffer;
  }

  static encodeInto(values: Uint32Array, output: ArrayBuffer): number {
    const view: DataView = new DataView(output);
    let offset: number = 0;
    for (let i = 0; i < values.length; i++) {
      view.setUint32(offset, values[i], true);
      offset += 4;
    }
    return offset;
  }

  static decode(buffer: ArrayBuffer, size: number): Uint32Array {
    if ((size % 4) !== 0) {
      throw new Error('Compact u32 payload size must align with uint32 elements.');
    }

    const view: DataView = new DataView(buffer, 0, size);
    const count: number = size / 4;
    const values: Uint32Array = new Uint32Array(count);
    for (let i = 0; i < count; i++) {
      values[i] = view.getUint32(i * 4, true);
    }
    return values;
  }

  static fingerprint(values: Uint32Array): bigint {
    if (values.length === 0) {
      return 0n;
    }

    const middle: number = Math.floor(values.length / 2);
    const MASK64: bigint = 0xffffffffffffffffn;
    const sum: bigint = BigInt(values.length)
      + BigInt(values[0] >>> 0)
      + BigInt(values[middle] >>> 0)
      + BigInt(values[values.length - 1] >>> 0);
    return sum & MASK64;
  }
}
