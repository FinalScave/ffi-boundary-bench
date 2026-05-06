import { BinaryModel } from './BinaryModel';
import { BinaryModelWireCodec, RECORD_HEADER_BYTES } from './BinaryModelWireCodec';

export class BinaryModelPayloadFactory {
  static createModelsForTargetBytes(targetBytes: number): Array<BinaryModel> {
    if (targetBytes < RECORD_HEADER_BYTES) {
      throw new Error('Target payload is too small for BinaryModel.');
    }

    const models: Array<BinaryModel> = [];
    let usedBytes = 0;
    let index = 0;

    while (usedBytes < targetBytes) {
      const remaining = targetBytes - usedBytes;
      if (remaining < RECORD_HEADER_BYTES) {
        if (models.length === 0) {
          throw new Error('BinaryModel payload generation failed.');
        }
        const last = models.pop() as BinaryModel;
        const extended = last.stringValue + BinaryModelPayloadFactory.buildAsciiString(index, remaining);
        models.push(new BinaryModel(last.intValue, last.floatValue, extended));
        usedBytes += remaining;
        break;
      }

      const maxStringBytes = remaining - RECORD_HEADER_BYTES;
      let stringBytes = Math.min(maxStringBytes, 12 + (index % 19));
      const tailBytes = remaining - RECORD_HEADER_BYTES - stringBytes;
      if (tailBytes > 0 && tailBytes < RECORD_HEADER_BYTES) {
        stringBytes += tailBytes;
      }

      models.push(BinaryModelPayloadFactory.makeBinaryModel(index, stringBytes));
      usedBytes += RECORD_HEADER_BYTES + stringBytes;
      index += 1;
    }

    if (BinaryModelWireCodec.encodedSize(models) !== targetBytes) {
      throw new Error('BinaryModel payload generation did not hit the requested size.');
    }
    return models;
  }

  private static makeBinaryModel(index: number, stringBytes: number): BinaryModel {
    const intValue = 1000 + (index * 17);
    const floatValue = 0.5 + (index * 0.25);
    const stringValue = BinaryModelPayloadFactory.buildAsciiString(index, stringBytes);
    return new BinaryModel(intValue, floatValue, stringValue);
  }

  private static buildAsciiString(index: number, targetBytes: number): string {
    let result = `model_${index}_`;
    if (result.length > targetBytes) {
      return result.substring(0, targetBytes);
    }
    while (result.length < targetBytes) {
      result += String.fromCharCode(97 + ((index + result.length) % 26));
    }
    return result;
  }
}
