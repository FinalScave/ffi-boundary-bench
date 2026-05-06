package com.ffibb.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class U32PayloadCodec {
  private U32PayloadCodec() {
  }

  static int[] createValuesForTargetBytes(int targetBytes) {
    if ((targetBytes % Integer.BYTES) != 0) {
      throw new IllegalArgumentException("Byte count must align with uint32 elements.");
    }

    int[] values = new int[targetBytes / Integer.BYTES];
    fillPattern(values);
    return values;
  }

  static byte[] encode(int[] values) {
    byte[] bytes = new byte[values.length * Integer.BYTES];
    int offset = 0;
    for (int value : values) {
      writeIntLE(bytes, offset, value);
      offset += Integer.BYTES;
    }
    return bytes;
  }

  static void encodeToBuffer(int[] values, ByteBuffer output) {
    int size = values.length * Integer.BYTES;
    if (output.capacity() < size) {
      throw new IllegalArgumentException("Direct buffer is too small for compact u32 payload.");
    }

    output.clear();
    output.order(ByteOrder.LITTLE_ENDIAN);
    for (int value : values) {
      output.putInt(value);
    }
    output.flip();
  }

  static int[] decode(byte[] bytes) {
    if ((bytes.length % Integer.BYTES) != 0) {
      throw new IllegalArgumentException("Compact u32 payload size must align with uint32 elements.");
    }

    int[] values = new int[bytes.length / Integer.BYTES];
    int offset = 0;
    for (int index = 0; index < values.length; index++) {
      values[index] = readIntLE(bytes, offset);
      offset += Integer.BYTES;
    }
    return values;
  }

  static int[] decode(ByteBuffer bytes) {
    ByteBuffer input = bytes.duplicate();
    input.order(ByteOrder.LITTLE_ENDIAN);
    if ((input.remaining() % Integer.BYTES) != 0) {
      throw new IllegalArgumentException("Compact u32 payload size must align with uint32 elements.");
    }

    int[] values = new int[input.remaining() / Integer.BYTES];
    for (int index = 0; index < values.length; index++) {
      values[index] = input.getInt();
    }
    return values;
  }

  static long fingerprint(int[] values) {
    if (values.length == 0) {
      return 0L;
    }

    int middle = values.length / 2;
    return values.length
        + Integer.toUnsignedLong(values[0])
        + Integer.toUnsignedLong(values[middle])
        + Integer.toUnsignedLong(values[values.length - 1]);
  }

  private static void fillPattern(int[] values) {
    for (int index = 0; index < values.length; index++) {
      long lhs = (index * 2654435761L) & 0xFFFFFFFFL;
      long rhs = (0x9E3779B9L + index * 17L) & 0xFFFFFFFFL;
      values[index] = (int) (lhs ^ rhs);
    }
  }

  private static void writeIntLE(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value & 0xFF);
    bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
    bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
  }

  private static int readIntLE(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF)
        | ((bytes[offset + 1] & 0xFF) << 8)
        | ((bytes[offset + 2] & 0xFF) << 16)
        | ((bytes[offset + 3] & 0xFF) << 24);
  }
}