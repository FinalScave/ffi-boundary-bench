#include "core/compact_u32_codec.h"

#include "core/benchmark_data.h"

#include <cstring>

namespace ffibb {

ByteBuffer EncodeCompactBytes(const ValueVector &values) {
  ByteBuffer bytes(values.size() * sizeof(std::uint32_t));
  if (!bytes.empty()) {
    std::memcpy(bytes.data(), values.data(), bytes.size());
  }
  return bytes;
}

ValueVector DecodeCompactBytes(const ByteBuffer &bytes) {
  const std::size_t count = ElementCountForBytes(bytes.size());
  ValueVector values(count);
  if (!bytes.empty()) {
    std::memcpy(values.data(), bytes.data(), bytes.size());
  }
  return values;
}

} // namespace ffibb
