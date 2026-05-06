#pragma once

#include "core/common_types.h"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ffibb {

struct BinaryModel {
  std::int32_t int_value = 0;
  float float_value = 0.0f;
  std::string string_value;
};

using BinaryModelVector = std::vector<BinaryModel>;

BinaryModelVector MakeBinaryModelPayload(std::size_t target_bytes);
ByteBuffer EncodeBinaryModels(const BinaryModelVector &models);
BinaryModelVector DecodeBinaryModels(const ByteBuffer &bytes);
std::size_t EncodedBinaryModelsSize(const BinaryModelVector &models);
std::uint64_t SampleBinaryModels(const BinaryModelVector &models);

} // namespace ffibb
