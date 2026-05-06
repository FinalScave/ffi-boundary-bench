package com.ffibb.android;

final class BenchmarkTimer {
  interface Task {
    long run();
  }

  private BenchmarkTimer() {
  }

  static BenchmarkMeasurement measure(int iterations, int warmupRounds, Task task) {
    for (int warmup = 0; warmup < warmupRounds; warmup++) {
      BenchmarkSink.add(task.run());
    }

    double totalMs = 0.0;
    double minMs = Double.MAX_VALUE;
    double maxMs = 0.0;

    for (int iteration = 0; iteration < iterations; iteration++) {
      long start = System.nanoTime();
      long sinkValue = task.run();
      long end = System.nanoTime();
      BenchmarkSink.add(sinkValue);

      double elapsedMs = (end - start) / 1_000_000.0;
      totalMs += elapsedMs;
      minMs = Math.min(minMs, elapsedMs);
      maxMs = Math.max(maxMs, elapsedMs);
    }

    return new BenchmarkMeasurement(totalMs / iterations, minMs, maxMs);
  }
}
