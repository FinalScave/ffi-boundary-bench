#pragma once

#include "core/common_types.h"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace ffibb {

struct BenchmarkCase {
  std::string name;
  std::size_t target_bytes;
  std::size_t iterations;
};

struct Measurement {
  double average_ms = 0.0;
  double min_ms = 0.0;
  double max_ms = 0.0;
};

struct BenchmarkRow {
  std::string binding;
  std::string operation;
  BenchmarkCase benchmark_case;
  Measurement measurement;
};

std::size_t ElementCountForBytes(std::size_t byte_count);
void FillPattern(std::uint32_t *values, std::size_t count);
ValueVector MakePayload(std::size_t target_bytes);
std::size_t SampleValues(const ValueVector &values);
std::size_t SampleBytes(const ByteBuffer &bytes);
std::string FormatSize(std::size_t bytes);
const std::vector<BenchmarkCase> &DefaultBenchmarkCases();
void WriteJsonResults(const std::string &path, const std::vector<BenchmarkRow> &rows);

template <typename Function>
Measurement MeasureIterations(std::size_t iterations, Function &&fn) {
  using Clock = std::chrono::steady_clock;
  using Milliseconds = std::chrono::duration<double, std::milli>;

  Measurement measurement;
  measurement.min_ms = std::numeric_limits<double>::max();

  for (std::size_t i = 0; i < iterations; ++i) {
    const auto begin = Clock::now();
    fn();
    const auto end = Clock::now();
    const double elapsed_ms = Milliseconds(end - begin).count();

    measurement.average_ms += elapsed_ms;
    measurement.min_ms = std::min(measurement.min_ms, elapsed_ms);
    measurement.max_ms = std::max(measurement.max_ms, elapsed_ms);
  }

  measurement.average_ms /= static_cast<double>(iterations);
  return measurement;
}

} // namespace ffibb
