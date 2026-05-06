package com.ffibb.android;

import java.util.ArrayList;
import java.util.List;

final class BinaryModelPayloadFactory {
  private BinaryModelPayloadFactory() {
  }

  static List<BinaryModel> createModelsForTargetBytes(int targetBytes) {
    if (targetBytes < BinaryModelWireCodec.RECORD_HEADER_BYTES) {
      throw new IllegalArgumentException("Target payload is too small for BinaryModel.");
    }

    ArrayList<BinaryModel> models = new ArrayList<>();
    int usedBytes = 0;
    int index = 0;

    while (usedBytes < targetBytes) {
      int remaining = targetBytes - usedBytes;
      if (remaining < BinaryModelWireCodec.RECORD_HEADER_BYTES) {
        if (models.isEmpty()) {
          throw new IllegalStateException("BinaryModel payload generation failed.");
        }

        BinaryModel last = models.remove(models.size() - 1);
        String extended = last.stringValue + buildAsciiString(index, remaining);
        models.add(new BinaryModel(last.intValue, last.floatValue, extended));
        usedBytes += remaining;
        break;
      }

      int maxStringBytes = remaining - BinaryModelWireCodec.RECORD_HEADER_BYTES;
      int stringBytes = Math.min(maxStringBytes, 12 + (index % 19));
      int tailBytes = remaining - BinaryModelWireCodec.RECORD_HEADER_BYTES - stringBytes;
      if (tailBytes > 0 && tailBytes < BinaryModelWireCodec.RECORD_HEADER_BYTES) {
        stringBytes += tailBytes;
      }

      models.add(makeBinaryModel(index, stringBytes));
      usedBytes += BinaryModelWireCodec.RECORD_HEADER_BYTES + stringBytes;
      index += 1;
    }

    if (BinaryModelWireCodec.encodedSize(models) != targetBytes) {
      throw new IllegalStateException("BinaryModel payload generation did not hit the requested size.");
    }

    return models;
  }

  private static BinaryModel makeBinaryModel(int index, int stringBytes) {
    int intValue = 1000 + (index * 17);
    float floatValue = 0.5f + (index * 0.25f);
    String stringValue = buildAsciiString(index, stringBytes);
    return new BinaryModel(intValue, floatValue, stringValue);
  }

  private static String buildAsciiString(int index, int targetBytes) {
    StringBuilder builder = new StringBuilder("model_").append(index).append('_');
    if (builder.length() > targetBytes) {
      builder.setLength(targetBytes);
      return builder.toString();
    }

    builder.ensureCapacity(targetBytes);
    while (builder.length() < targetBytes) {
      builder.append((char) ('a' + ((index + builder.length()) % 26)));
    }

    return builder.toString();
  }
}
