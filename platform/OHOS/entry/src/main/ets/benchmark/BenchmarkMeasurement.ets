export class BenchmarkMeasurement {
  public readonly averageMs: number;
  public readonly minMs: number;
  public readonly maxMs: number;

  constructor(averageMs: number, minMs: number, maxMs: number) {
    this.averageMs = averageMs;
    this.minMs = minMs;
    this.maxMs = maxMs;
  }
}
