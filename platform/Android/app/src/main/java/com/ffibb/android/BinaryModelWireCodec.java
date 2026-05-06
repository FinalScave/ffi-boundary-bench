package com.ffibb.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class BinaryModelWireCodec {
  static final int RECORD_HEADER_BYTES = 4 + 4 + 4;

  private BinaryModelWireCodec() {
  }

  static byte[] encode(List<BinaryModel> models) {
    byte[] bytes = new byte[encodedSize(models)];
    int offset = 0;

    for (BinaryModel model : models) {
      writeIntLE(bytes, offset, model.intValue);
      offset += 4;
      writeIntLE(bytes, offset, Float.floatToRawIntBits(model.floatValue));
      offset += 4;

      byte[] stringBytes = model.stringValue.getBytes(StandardCharsets.UTF_8);
      writeIntLE(bytes, offset, stringBytes.length);
      offset += 4;

      System.arraycopy(stringBytes, 0, bytes, offset, stringBytes.length);
      offset += stringBytes.length;
    }

    return bytes;
  }

  static void encodeToBuffer(List<BinaryModel> models, ByteBuffer output) {
    int size = encodedSize(models);
    if (output.capacity() < size) {
      throw new IllegalArgumentException("Direct buffer is too small for compact payload.");
    }

    output.clear();
    output.order(ByteOrder.LITTLE_ENDIAN);

    for (BinaryModel model : models) {
      output.putInt(model.intValue);
      output.putInt(Float.floatToRawIntBits(model.floatValue));

      byte[] stringBytes = model.stringValue.getBytes(StandardCharsets.UTF_8);
      output.putInt(stringBytes.length);
      output.put(stringBytes);
    }

    output.flip();
  }

  static List<BinaryModel> decode(byte[] bytes) {
    ArrayList<BinaryModel> models = new ArrayList<>();
    int offset = 0;

    while (offset < bytes.length) {
      if (bytes.length - offset < RECORD_HEADER_BYTES) {
        throw new IllegalArgumentException("Unexpected end of compact byte payload.");
      }

      int intValue = readIntLE(bytes, offset);
      offset += 4;

      float floatValue = Float.intBitsToFloat(readIntLE(bytes, offset));
      offset += 4;

      int stringSize = readIntLE(bytes, offset);
      offset += 4;

      if (stringSize < 0 || bytes.length - offset < stringSize) {
        throw new IllegalArgumentException("String size exceeds compact payload size.");
      }

      String stringValue = new String(bytes, offset, stringSize, StandardCharsets.UTF_8);
      offset += stringSize;
      models.add(new BinaryModel(intValue, floatValue, stringValue));
    }

    return models;
  }

  static List<BinaryModel> decode(ByteBuffer bytes) {
    ByteBuffer input = bytes.duplicate();
    input.order(ByteOrder.LITTLE_ENDIAN);

    ArrayList<BinaryModel> models = new ArrayList<>();
    while (input.hasRemaining()) {
      if (input.remaining() < RECORD_HEADER_BYTES) {
        throw new IllegalArgumentException("Unexpected end of compact byte payload.");
      }

      int intValue = input.getInt();
      float floatValue = Float.intBitsToFloat(input.getInt());
      int stringSize = input.getInt();

      if (stringSize < 0 || input.remaining() < stringSize) {
        throw new IllegalArgumentException("String size exceeds compact payload size.");
      }

      byte[] stringBytes = new byte[stringSize];
      input.get(stringBytes);
      models.add(new BinaryModel(intValue, floatValue, new String(stringBytes, StandardCharsets.UTF_8)));
    }

    return models;
  }

  static int encodedSize(List<BinaryModel> models) {
    int totalBytes = 0;
    for (BinaryModel model : models) {
      totalBytes += RECORD_HEADER_BYTES + model.stringValue.getBytes(StandardCharsets.UTF_8).length;
    }
    return totalBytes;
  }

  static long fingerprint(List<BinaryModel> models) {
    if (models.isEmpty()) {
      return 0L;
    }

    int middle = models.size() / 2;
    BinaryModel[] samples = new BinaryModel[] {
        models.get(0),
        models.get(middle),
        models.get(models.size() - 1)
    };

    long fingerprint = models.size();
    for (BinaryModel model : samples) {
      fingerprint = (fingerprint * 1315423911L) ^ (model.intValue & 0xFFFFFFFFL);
      fingerprint = (fingerprint * 1315423911L) ^ (Float.floatToRawIntBits(model.floatValue) & 0xFFFFFFFFL);

      byte[] stringBytes = model.stringValue.getBytes(StandardCharsets.UTF_8);
      fingerprint = (fingerprint * 1315423911L) ^ stringBytes.length;
      for (byte value : stringBytes) {
        fingerprint = (fingerprint * 1099511628211L) ^ (value & 0xFFL);
      }
    }

    return fingerprint;
  }

  private static void writeIntLE(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value & 0xFF);
    bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
    bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
  }

  private static int readIntLE(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF) |
        ((bytes[offset + 1] & 0xFF) << 8) |
        ((bytes[offset + 2] & 0xFF) << 16) |
        ((bytes[offset + 3] & 0xFF) << 24);
  }
}
