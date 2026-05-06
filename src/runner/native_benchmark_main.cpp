#include "ffibb/ffibb.h"

#include "core/benchmark_data.h"
#include "core/binary_model_codec.h"
#include "core/compact_u32_codec.h"

#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

volatile std::size_t g_sink = 0;

ffibb::BenchmarkRow RunCApiEncodeCase(const ffibb::BenchmarkCase &benchmark_case) {
  ffibb_owned_bytes warmup{};
  if (ffibb_build_sample_u32_bytes(benchmark_case.target_bytes, &warmup, nullptr) != FFIBB_STATUS_OK) {
    throw std::runtime_error("Warmup C API encode failed.");
  }
  g_sink += warmup.size;
  ffibb_free_bytes(&warmup);

  return {
      "c_api",
      "u32_sample_to_owned_bytes",
      benchmark_case,
      ffibb::MeasureIterations(benchmark_case.iterations, [&benchmark_case]() {
        ffibb_owned_bytes encoded{};
        const ffibb_status status = ffibb_build_sample_u32_bytes(benchmark_case.target_bytes, &encoded, nullptr);
        if (status != FFIBB_STATUS_OK) {
          throw std::runtime_error(std::string("C API encode failed: ") + ffibb_status_string(status));
        }
        g_sink += encoded.size;
        ffibb_free_bytes(&encoded);
      }),
  };
}

ffibb::BenchmarkRow RunCApiDecodeCase(const ffibb::BenchmarkCase &benchmark_case) {
  const ffibb::ValueVector payload = ffibb::MakePayload(benchmark_case.target_bytes);
  const ffibb::ByteBuffer bytes = ffibb::EncodeCompactBytes(payload);
  const std::size_t expected_count = payload.size();
  const std::uint64_t expected_fingerprint = static_cast<std::uint64_t>(ffibb::SampleValues(payload));

  std::uint64_t warmup_fingerprint = 0;
  if (ffibb_process_u32_bytes(bytes.data(), bytes.size(), &warmup_fingerprint) != FFIBB_STATUS_OK) {
    throw std::runtime_error("Warmup C API decode failed.");
  }
  if (warmup_fingerprint != expected_fingerprint) {
    throw std::runtime_error("Warmup C API decode produced an unexpected fingerprint.");
  }
  g_sink += expected_count + static_cast<std::size_t>(warmup_fingerprint);

  return {
      "c_api",
      "u32_bytes_to_c_api",
      benchmark_case,
      ffibb::MeasureIterations(benchmark_case.iterations, [&bytes, expected_fingerprint, expected_count]() {
        std::uint64_t fingerprint = 0;
        const ffibb_status status = ffibb_process_u32_bytes(bytes.data(), bytes.size(), &fingerprint);
        if (status != FFIBB_STATUS_OK) {
          throw std::runtime_error(std::string("C API decode failed: ") + ffibb_status_string(status));
        }
        if (fingerprint != expected_fingerprint) {
          throw std::runtime_error("C API decode produced an unexpected fingerprint.");
        }
        g_sink += expected_count + static_cast<std::size_t>(fingerprint);
      }),
  };
}

ffibb::BenchmarkRow RunBinaryModelCApiDecodeCase(const ffibb::BenchmarkCase &benchmark_case) {
  const ffibb::BinaryModelVector models = ffibb::MakeBinaryModelPayload(benchmark_case.target_bytes);
  const ffibb::ByteBuffer bytes = ffibb::EncodeBinaryModels(models);
  const std::uint64_t expected_fingerprint = ffibb::SampleBinaryModels(models);

  std::size_t warmup_count = 0;
  std::uint64_t warmup_fingerprint = 0;
  if (ffibb_process_binary_model_bytes(bytes.data(), bytes.size(), &warmup_count, &warmup_fingerprint) != FFIBB_STATUS_OK) {
    throw std::runtime_error("Warmup BinaryModel C API decode failed.");
  }
  if (warmup_count != models.size() || warmup_fingerprint != expected_fingerprint) {
    throw std::runtime_error("Warmup BinaryModel C API decode produced an unexpected fingerprint.");
  }
  g_sink += warmup_count + static_cast<std::size_t>(warmup_fingerprint);

  return {
      "c_api",
      "binary_model_bytes_to_c_api",
      benchmark_case,
      ffibb::MeasureIterations(benchmark_case.iterations, [&bytes, expected_fingerprint, expected_count = models.size()]() {
        std::size_t count = 0;
        std::uint64_t fingerprint = 0;
        const ffibb_status status = ffibb_process_binary_model_bytes(bytes.data(), bytes.size(), &count, &fingerprint);
        if (status != FFIBB_STATUS_OK) {
          throw std::runtime_error(std::string("BinaryModel C API decode failed: ") + ffibb_status_string(status));
        }
        if (count != expected_count || fingerprint != expected_fingerprint) {
          throw std::runtime_error("BinaryModel C API decode produced an unexpected fingerprint.");
        }
        g_sink += count + static_cast<std::size_t>(fingerprint);
      }),
  };
}

ffibb::BenchmarkRow RunBinaryModelCApiEncodeCase(const ffibb::BenchmarkCase &benchmark_case) {
  const ffibb::BinaryModelVector models = ffibb::MakeBinaryModelPayload(benchmark_case.target_bytes);
  const std::size_t expected_count = models.size();
  const std::uint64_t expected_fingerprint = ffibb::SampleBinaryModels(models);

  ffibb_owned_bytes warmup{};
  std::size_t warmup_count = 0;
  std::uint64_t warmup_fingerprint = 0;
  if (ffibb_build_sample_binary_model_bytes(
      benchmark_case.target_bytes, &warmup, &warmup_count, &warmup_fingerprint) != FFIBB_STATUS_OK) {
    throw std::runtime_error("Warmup BinaryModel C API encode failed.");
  }
  if (warmup_count != expected_count || warmup_fingerprint != expected_fingerprint) {
    ffibb_free_bytes(&warmup);
    throw std::runtime_error("Warmup BinaryModel C API encode produced an unexpected fingerprint.");
  }
  g_sink += warmup.size + warmup_count + static_cast<std::size_t>(warmup_fingerprint);
  ffibb_free_bytes(&warmup);

  return {
      "c_api",
      "binary_model_sample_to_owned_bytes",
      benchmark_case,
      ffibb::MeasureIterations(benchmark_case.iterations, [benchmark_case, expected_count, expected_fingerprint]() {
        ffibb_owned_bytes encoded{};
        std::size_t encoded_count = 0;
        std::uint64_t fingerprint = 0;
        const ffibb_status status = ffibb_build_sample_binary_model_bytes(
            benchmark_case.target_bytes, &encoded, &encoded_count, &fingerprint);
        if (status != FFIBB_STATUS_OK) {
          throw std::runtime_error(std::string("BinaryModel C API encode failed: ") + ffibb_status_string(status));
        }
        if (encoded_count != expected_count || fingerprint != expected_fingerprint) {
          ffibb_free_bytes(&encoded);
          throw std::runtime_error("BinaryModel C API encode produced an unexpected fingerprint.");
        }
        g_sink += encoded.size + encoded_count + static_cast<std::size_t>(fingerprint);
        ffibb_free_bytes(&encoded);
      }),
  };
}

void PrintDatasets(const std::vector<ffibb::BenchmarkCase> &cases) {
  std::cout << "\nDatasets\n";
  for (const auto &benchmark_case : cases) {
    const std::size_t binary_model_count = ffibb::MakeBinaryModelPayload(benchmark_case.target_bytes).size();
    std::cout << "  " << std::left << std::setw(8) << benchmark_case.name << " size=" << std::setw(10)
              << ffibb::FormatSize(benchmark_case.target_bytes)
              << " iterations=" << benchmark_case.iterations
              << " binary_models=" << binary_model_count
              << " u32_elements=" << ffibb::ElementCountForBytes(benchmark_case.target_bytes) << '\n';
  }
}

bool IsBinaryModelRow(const ffibb::BenchmarkRow &row) {
  return row.operation.find("binary_model") != std::string::npos;
}

std::size_t ElementCountForRow(const ffibb::BenchmarkRow &row) {
  if (IsBinaryModelRow(row)) {
    return ffibb::MakeBinaryModelPayload(row.benchmark_case.target_bytes).size();
  }

  return ffibb::ElementCountForBytes(row.benchmark_case.target_bytes);
}

void PrintRowsForPayloadFamily(
    const std::vector<ffibb::BenchmarkRow> &rows,
    const char *title,
    bool binary_model_rows) {
  std::cout << "\n" << title << "\n";
  std::cout << std::left << std::setw(12) << "Binding"
            << std::setw(38) << "Operation"
            << std::setw(12) << "Case"
            << std::right << std::setw(12) << "Iterations"
            << std::setw(12) << "Elements"
            << std::setw(14) << "Avg (ms)"
            << std::setw(14) << "Min (ms)"
            << std::setw(14) << "Max (ms)"
            << std::setw(14) << "Bytes" << '\n';

  for (const auto &row : rows) {
    if (IsBinaryModelRow(row) != binary_model_rows) {
      continue;
    }

    std::cout << std::left << std::setw(12) << row.binding
              << std::setw(38) << row.operation
              << std::setw(12) << row.benchmark_case.name
              << std::right << std::setw(12) << row.benchmark_case.iterations
              << std::setw(12) << ElementCountForRow(row)
              << std::setw(14) << std::fixed << std::setprecision(3) << row.measurement.average_ms
              << std::setw(14) << row.measurement.min_ms
              << std::setw(14) << row.measurement.max_ms
              << std::setw(14) << row.benchmark_case.target_bytes << '\n';
  }
}

void PrintRows(const std::vector<ffibb::BenchmarkRow> &rows) {
  PrintRowsForPayloadFamily(rows, "u32 Baseline", false);
  PrintRowsForPayloadFamily(rows, "BinaryModel", true);
}

std::string ParseJsonPath(int argc, char **argv) {
  for (int index = 1; index + 1 < argc; ++index) {
    if (std::strcmp(argv[index], "--json") == 0) {
      return argv[index + 1];
    }
  }

  return {};
}

} // namespace

int main(int argc, char **argv) {
  try {
    const std::vector<ffibb::BenchmarkCase> cases = ffibb::DefaultBenchmarkCases();
    std::vector<ffibb::BenchmarkRow> rows;
    rows.reserve(cases.size() * 4);

    std::cout << "ffi-binary-bench native baseline\n";
    std::cout << "Value types: uint32_t baseline, BinaryModel current C API path\n";
    std::cout << "Wire formats: raw contiguous u32 bytes, compact BinaryModel records\n";
#ifndef NDEBUG
    std::cout << "Debug builds distort timing. Use Release for meaningful comparisons.\n";
#endif

    PrintDatasets(cases);

    for (const auto &benchmark_case : cases) {
      rows.push_back(RunCApiEncodeCase(benchmark_case));
      rows.push_back(RunCApiDecodeCase(benchmark_case));
      rows.push_back(RunBinaryModelCApiDecodeCase(benchmark_case));
      rows.push_back(RunBinaryModelCApiEncodeCase(benchmark_case));
    }

    PrintRows(rows);

    const std::string json_path = ParseJsonPath(argc, argv);
    if (!json_path.empty()) {
      ffibb::WriteJsonResults(json_path, rows);
      std::cout << "\nJSON: " << json_path << '\n';
    }

    std::cout << "\nSink: " << g_sink << '\n';
    return EXIT_SUCCESS;
  } catch (const std::exception &exception) {
    std::cerr << "Benchmark failed: " << exception.what() << '\n';
    return EXIT_FAILURE;
  }
}