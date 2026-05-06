#include "core/benchmark_data.h"

#include <fstream>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace ffibb {

namespace {

std::string EscapeJson(const std::string &value) {
  std::string escaped;
  escaped.reserve(value.size());

  for (const char ch : value) {
    switch (ch) {
      case '\\':
        escaped += "\\\\";
        break;
      case '"':
        escaped += "\\\"";
        break;
      case '\n':
        escaped += "\\n";
        break;
      default:
        escaped += ch;
        break;
    }
  }

  return escaped;
}

} // namespace

std::size_t ElementCountForBytes(std::size_t byte_count) {
  if (byte_count % sizeof(std::uint32_t) != 0) {
    throw std::runtime_error("Byte count must align with uint32_t elements.");
  }

  return byte_count / sizeof(std::uint32_t);
}

void FillPattern(std::uint32_t *values, std::size_t count) {
  if (values == nullptr && count != 0) {
    throw std::runtime_error("Cannot fill a null payload buffer.");
  }

  for (std::size_t index = 0; index < count; ++index) {
    values[index] = static_cast<std::uint32_t>(
        (index * 2654435761u) ^ (0x9E3779B9u + static_cast<std::uint32_t>(index * 17u)));
  }
}

ValueVector MakePayload(std::size_t target_bytes) {
  const std::size_t count = ElementCountForBytes(target_bytes);
  ValueVector values(count);
  FillPattern(values.data(), values.size());
  return values;
}

std::size_t SampleValues(const ValueVector &values) {
  if (values.empty()) {
    return 0;
  }

  const std::size_t middle = values.size() / 2;
  return values.size() + values.front() + values[middle] + values.back();
}

std::size_t SampleBytes(const ByteBuffer &bytes) {
  if (bytes.empty()) {
    return 0;
  }

  const std::size_t middle = bytes.size() / 2;
  return bytes.size() + bytes.front() + bytes[middle] + bytes.back();
}

std::string FormatSize(std::size_t bytes) {
  constexpr double kKiB = 1024.0;
  constexpr double kMiB = 1024.0 * 1024.0;

  std::ostringstream stream;
  stream << std::fixed << std::setprecision(2);

  if (bytes >= static_cast<std::size_t>(kMiB)) {
    stream << (static_cast<double>(bytes) / kMiB) << " MiB";
  } else if (bytes >= static_cast<std::size_t>(kKiB)) {
    stream << (static_cast<double>(bytes) / kKiB) << " KiB";
  } else {
    stream.unsetf(std::ios::floatfield);
    stream << bytes << " B";
  }

  return stream.str();
}

const std::vector<BenchmarkCase> &DefaultBenchmarkCases() {
  static const std::vector<BenchmarkCase> cases = {
      {"10 KiB", 10 * 1024, 5000},
      {"200 KiB", 200 * 1024, 1000},
      {"1 MiB", 1024 * 1024, 200},
  };
  return cases;
}

void WriteJsonResults(const std::string &path, const std::vector<BenchmarkRow> &rows) {
  std::ofstream stream(path, std::ios::out | std::ios::trunc);
  if (!stream) {
    throw std::runtime_error("Failed to open JSON output file: " + path);
  }

  stream << "{\n";
  stream << "  \"rows\": [\n";

  for (std::size_t index = 0; index < rows.size(); ++index) {
    const BenchmarkRow &row = rows[index];
    stream << "    {\n";
    stream << "      \"binding\": \"" << EscapeJson(row.binding) << "\",\n";
    stream << "      \"operation\": \"" << EscapeJson(row.operation) << "\",\n";
    stream << "      \"case_name\": \"" << EscapeJson(row.benchmark_case.name) << "\",\n";
    stream << "      \"payload_bytes\": " << row.benchmark_case.target_bytes << ",\n";
    stream << "      \"element_count\": " << ElementCountForBytes(row.benchmark_case.target_bytes) << ",\n";
    stream << "      \"iterations\": " << row.benchmark_case.iterations << ",\n";
    stream << "      \"average_ms\": " << std::fixed << std::setprecision(6) << row.measurement.average_ms << ",\n";
    stream << "      \"min_ms\": " << row.measurement.min_ms << ",\n";
    stream << "      \"max_ms\": " << row.measurement.max_ms << '\n';
    stream << "    }";
    if (index + 1 != rows.size()) {
      stream << ",";
    }
    stream << '\n';
  }

  stream << "  ]\n";
  stream << "}\n";
}

} // namespace ffibb
