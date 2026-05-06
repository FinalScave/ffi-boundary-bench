const MASK64: bigint = 0xffffffffffffffffn;

export class BenchmarkSink {
  private static value: bigint = 0n;

  static reset(): void {
    BenchmarkSink.value = 0n;
  }

  static add(contribution: bigint): void {
    BenchmarkSink.value = (BenchmarkSink.value + contribution) & MASK64;
  }

  static get(): bigint {
    return BenchmarkSink.value;
  }
}
