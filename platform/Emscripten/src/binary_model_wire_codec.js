const RECORD_HEADER_BYTES = 12;
const FINGERPRINT_MULTIPLIER = 1315423911n;
const STRING_FINGERPRINT_MULTIPLIER = 1099511628211n;

const scratchBuffer = new ArrayBuffer(4);
const scratchView = new DataView(scratchBuffer);
const textDecoder = new TextDecoder();

function writeU32LE(bytes, offset, value) {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = (value >>> 8) & 0xff;
  bytes[offset + 2] = (value >>> 16) & 0xff;
  bytes[offset + 3] = (value >>> 24) & 0xff;
}

function readU32LE(dataView, offset) {
  return dataView.getUint32(offset, true);
}

function floatBits(value) {
  scratchView.setFloat32(0, Math.fround(value), true);
  return scratchView.getUint32(0, true);
}

function floatFromBits(bits) {
  scratchView.setUint32(0, bits, true);
  return scratchView.getFloat32(0, true);
}

function buildAsciiString(index, targetBytes) {
  let value = `model_${index}_`;
  if (value.length > targetBytes) {
    return value.slice(0, targetBytes);
  }

  while (value.length < targetBytes) {
    value += String.fromCharCode(97 + ((index + value.length) % 26));
  }
  return value;
}

function makeBinaryModel(index, stringBytes) {
  const floatValue = Math.fround(0.5 + index * 0.25);
  return {
    intValue: 1000 + index * 17,
    floatValue,
    floatBits: floatBits(floatValue),
    stringValue: buildAsciiString(index, stringBytes),
  };
}

function createModelsForTargetBytes(targetBytes) {
  if (targetBytes < RECORD_HEADER_BYTES) {
    throw new Error("Target payload is too small for BinaryModel.");
  }

  const models = [];
  let usedBytes = 0;
  let index = 0;

  while (usedBytes < targetBytes) {
    const remaining = targetBytes - usedBytes;
    if (remaining < RECORD_HEADER_BYTES) {
      if (models.length === 0) {
        throw new Error("BinaryModel payload generation failed.");
      }
      models[models.length - 1].stringValue += buildAsciiString(index, remaining);
      usedBytes += remaining;
      break;
    }

    const maxStringBytes = remaining - RECORD_HEADER_BYTES;
    let stringBytes = Math.min(maxStringBytes, 12 + (index % 19));
    const tailBytes = remaining - RECORD_HEADER_BYTES - stringBytes;
    if (tailBytes > 0 && tailBytes < RECORD_HEADER_BYTES) {
      stringBytes += tailBytes;
    }

    models.push(makeBinaryModel(index, stringBytes));
    usedBytes += RECORD_HEADER_BYTES + stringBytes;
    index += 1;
  }

  if (encodedSize(models) !== targetBytes) {
    throw new Error("BinaryModel payload generation did not hit the requested size.");
  }

  return models;
}

function encodedSize(models) {
  let totalBytes = 0;
  for (const model of models) {
    totalBytes += RECORD_HEADER_BYTES + model.stringValue.length;
  }
  return totalBytes;
}

function encodeTo(models, bytes, offset = 0) {
  let cursor = offset;
  for (const model of models) {
    writeU32LE(bytes, cursor, model.intValue >>> 0);
    cursor += 4;
    writeU32LE(bytes, cursor, model.floatBits >>> 0);
    cursor += 4;
    writeU32LE(bytes, cursor, model.stringValue.length);
    cursor += 4;

    for (let index = 0; index < model.stringValue.length; index += 1) {
      bytes[cursor + index] = model.stringValue.charCodeAt(index) & 0xff;
    }
    cursor += model.stringValue.length;
  }
}

function encode(models) {
  const bytes = new Uint8Array(encodedSize(models));
  encodeTo(models, bytes);
  return bytes;
}

function encodeToHeap(models, module, pointer) {
  const bytes = module.HEAPU8;
  encodeTo(models, bytes, pointer);
}

function decode(bytes) {
  const dataView = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const models = [];
  let offset = 0;

  while (offset < bytes.byteLength) {
    const intValue = readU32LE(dataView, offset) | 0;
    offset += 4;
    const bits = readU32LE(dataView, offset);
    offset += 4;
    const stringSize = readU32LE(dataView, offset);
    offset += 4;

    if (offset + stringSize > bytes.byteLength) {
      throw new Error("BinaryModel string exceeds compact payload size.");
    }

    const stringValue = textDecoder.decode(bytes.subarray(offset, offset + stringSize));
    offset += stringSize;

    models.push({
      intValue,
      floatValue: floatFromBits(bits),
      floatBits: bits,
      stringValue,
    });
  }

  return models;
}

function fingerprint(models) {
  if (models.length === 0) {
    return 0n;
  }

  const middle = Math.floor(models.length / 2);
  const samples = [models[0], models[middle], models[models.length - 1]];
  let value = BigInt(models.length);

  for (const model of samples) {
    value = BigInt.asUintN(64, (value * FINGERPRINT_MULTIPLIER) ^ BigInt(model.intValue >>> 0));
    value = BigInt.asUintN(64, (value * FINGERPRINT_MULTIPLIER) ^ BigInt(model.floatBits >>> 0));
    value = BigInt.asUintN(64, (value * FINGERPRINT_MULTIPLIER) ^ BigInt(model.stringValue.length));
    for (let index = 0; index < model.stringValue.length; index += 1) {
      value = BigInt.asUintN(
        64,
        (value * STRING_FINGERPRINT_MULTIPLIER) ^ BigInt(model.stringValue.charCodeAt(index) & 0xff),
      );
    }
  }

  return value;
}

module.exports = {
  createModelsForTargetBytes,
  decode,
  encode,
  encodeToHeap,
  encodedSize,
  fingerprint,
};
