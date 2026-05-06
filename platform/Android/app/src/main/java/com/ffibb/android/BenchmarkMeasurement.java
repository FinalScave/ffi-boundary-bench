package com.ffibb.android;

final class BenchmarkMeasurement {
  final double averageMs;
  final double minMs;
  final double maxMs;

  BenchmarkMeasurement(double averageMs, double minMs, double maxMs) {
    this.averageMs = averageMs;
    this.minMs = minMs;
    this.maxMs = maxMs;
  }
}
