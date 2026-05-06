import { BenchmarkBridge } from './BenchmarkBridge';
import { BenchmarkMeasurement } from './BenchmarkMeasurement';
import { BenchmarkSink } from './BenchmarkSink';

export type BenchmarkTask = () => bigint;

export class BenchmarkTimer {
  static measure(iterations: number, warmupRounds: number, task: BenchmarkTask): BenchmarkMeasurement {
    for (let i = 0; i < warmupRounds; i++) {
      BenchmarkSink.add(task());
    }

    let totalNs = 0n;
    let minNs = 0x7fffffffffffffffn;
    let maxNs = 0n;

    for (let i = 0; i < iterations; i++) {
      const begin = BenchmarkBridge.nanoTime();
      const value = task();
      const end = BenchmarkBridge.nanoTime();
      BenchmarkSink.add(value);

      const elapsed = end - begin;
      totalNs += elapsed;
      if (elapsed < minNs) {
        minNs = elapsed;
      }
      if (elapsed > maxNs) {
        maxNs = elapsed;
      }
    }

    const totalMs = Number(totalNs) / 1e6;
    return new BenchmarkMeasurement(
      totalMs / iterations,
      Number(minNs) / 1e6,
      Number(maxNs) / 1e6,
    );
  }
}
