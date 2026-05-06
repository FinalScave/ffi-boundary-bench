package com.ffibb.android;

final class BenchmarkSink {
  private static volatile long value;

  private BenchmarkSink() {
  }

  static void add(long contribution) {
    value += contribution;
  }

  static void reset() {
    value = 0L;
  }

  static long value() {
    return value;
  }
}
