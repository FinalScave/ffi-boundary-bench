package com.ffibb.android;

final class BenchmarkRow {
  final String payloadFamily;
  final String binding;
  final String operation;
  final String caseName;
  final int targetBytes;
  final int iterations;
  final int elementCount;
  final BenchmarkMeasurement measurement;

  BenchmarkRow(
    String payloadFamily,
      String binding,
      String operation,
      String caseName,
      int targetBytes,
      int iterations,
      int elementCount,
      BenchmarkMeasurement measurement) {
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
