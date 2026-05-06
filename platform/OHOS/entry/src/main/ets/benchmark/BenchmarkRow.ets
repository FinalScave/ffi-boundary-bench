import { BenchmarkMeasurement } from './BenchmarkMeasurement';

export const PAYLOAD_FAMILY_BINARY_MODEL: string = 'BinaryModel';
export const PAYLOAD_FAMILY_U32: string = 'u32';

export class BenchmarkRow {
  public readonly payloadFamily: string;
  public readonly binding: string;
  public readonly operation: string;
  public readonly caseName: string;
  public readonly targetBytes: number;
  public readonly iterations: number;
  public readonly elementCount: number;
  public readonly measurement: BenchmarkMeasurement;

  constructor(
    payloadFamily: string,
    binding: string,
    operation: string,
    caseName: string,
    targetBytes: number,
    iterations: number,
    elementCount: number,
    measurement: BenchmarkMeasurement,
  ) {
    this.payloadFamily = payloadFamily;
    this.binding = binding;
    this.operation = operation;
    this.caseName = caseName;
    this.targetBytes = targetBytes;
    this.iterations = iterations;
    this.elementCount = elementCount;
    this.measurement = measurement;
  }
}
