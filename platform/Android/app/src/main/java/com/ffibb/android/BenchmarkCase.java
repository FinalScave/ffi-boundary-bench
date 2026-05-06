package com.ffibb.android;

final class BenchmarkCase {
  final String name;
  final int targetBytes;
  final int iterations;

  BenchmarkCase(String name, int targetBytes, int iterations) {
    this.name = name;
    this.targetBytes = targetBytes;
    this.iterations = iterations;
  }
}

