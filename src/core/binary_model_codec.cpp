#include "core/binary_model_codec.h"

#include <algorithm>
#include <cstring>
#include <stdexcept>

namespace ffibb {

namespace {

constexpr std::size_t kRecordHeaderBytes =
    sizeof(std::int32_t) + sizeof(std::uint32_t) + sizeof(std::uint32_t);

std::uint32_t FloatBits(float value) {
  std::uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

float FloatFromBits(std::uint32_t bits) {
  float value = 0.0f;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

void WriteU32LE(ByteBuffer &bytes, std::size_t &offset, std::uint32_t value) {
  bytes[offset++] = static_cast<std::uint8_t>(value & 0xFFu);
  bytes[offset++] = static_cast<std::uint8_t>((value >> 8u) & 0xFFu);
  bytes[offset++] = static_cast<std::uint8_t>((value >> 16u) & 0xFFu);
  bytes[offset++] = static_cast<std::uint8_t>((value >> 24u) & 0xFFu);
}

std::uint32_t ReadU32LE(const ByteBuffer &bytes, std::size_t &offset) {
  if (offset + sizeof(std::uint32_t) > bytes.size()) {
    throw std::runtime_error("Unexpected end of compact byte payload.");
  }

  const std::uint32_t value = static_cast<std::uint32_t>(bytes[offset]) |
                              (static_cast<std::uint32_t>(bytes[offset + 1]) << 8u) |
                              (static_cast<std::uint32_t>(bytes[offset + 2]) << 16u) |
                              (static_cast<std::uint32_t>(bytes[offset + 3]) << 24u);
  offset += sizeof(std::uint32_t);
  return value;
}

std::string BuildAsciiString(std::size_t index, std::size_t target_bytes) {
  std::string value = "model_" + std::to_string(index) + "_";
  if (value.size() > target_bytes) {
    value.resize(target_bytes);
    return value;
  }

  value.reserve(target_bytes);
  while (value.size() < target_bytes) {
    value.push_back(static_cast<char>('a' + ((index + value.size()) % 26)));
  }

  return value;
}

BinaryModel MakeBinaryModel(std::size_t index, std::size_t string_bytes) {
  BinaryModel model;
  model.int_value = static_cast<std::int32_t>(1000 + (index * 17));
  model.float_value = static_cast<float>(0.5f + static_cast<float>(index) * 0.25f);
  model.string_value = BuildAsciiString(index, string_bytes);
  return model;
}

} // namespace

BinaryModelVector MakeBinaryModelPayload(std::size_t target_bytes) {
  if (target_bytes < kRecordHeaderBytes) {
    throw std::runtime_error("Target payload is too small for BinaryModel.");
  }

  BinaryModelVector models;
  std::size_t used_bytes = 0;
  std::size_t index = 0;

  while (used_bytes < target_bytes) {
    const std::size_t remaining = target_bytes - used_bytes;
    if (remaining < kRecordHeaderBytes) {
      if (models.empty()) {
        throw std::runtime_error("BinaryModel payload generation failed.");
      }
      models.back().string_value += BuildAsciiString(index, remaining);
      used_bytes += remaining;
      break;
    }

    const std::size_t max_string_bytes = remaining - kRecordHeaderBytes;
    std::size_t string_bytes = std::min<std::size_t>(max_string_bytes, 12 + (index % 19));
    const std::size_t tail_bytes = remaining - kRecordHeaderBytes - string_bytes;
    if (tail_bytes > 0 && tail_bytes < kRecordHeaderBytes) {
      string_bytes += tail_bytes;
    }

    models.push_back(MakeBinaryModel(index, string_bytes));
    used_bytes += kRecordHeaderBytes + string_bytes;
    ++index;
  }

  if (EncodedBinaryModelsSize(models) != target_bytes) {
    throw std::runtime_error("BinaryModel payload generation did not hit the requested size.");
  }

  return models;
}

ByteBuffer EncodeBinaryModels(const BinaryModelVector &models) {
  ByteBuffer bytes(EncodedBinaryModelsSize(models));
  std::size_t offset = 0;

  for (const auto &model : models) {
    WriteU32LE(bytes, offset, static_cast<std::uint32_t>(model.int_value));
    WriteU32LE(bytes, offset, FloatBits(model.float_value));
    WriteU32LE(bytes, offset, static_cast<std::uint32_t>(model.string_value.size()));
    if (!model.string_value.empty()) {
      std::memcpy(bytes.data() + offset, model.string_value.data(), model.string_value.size());
      offset += model.string_value.size();
    }
  }

  return bytes;
}

BinaryModelVector DecodeBinaryModels(const ByteBuffer &bytes) {
  BinaryModelVector models;
  std::size_t offset = 0;

  while (offset < bytes.size()) {
    BinaryModel model;
    model.int_value = static_cast<std::int32_t>(ReadU32LE(bytes, offset));
    model.float_value = FloatFromBits(ReadU32LE(bytes, offset));
    const std::uint32_t string_size = ReadU32LE(bytes, offset);
    if (offset + string_size > bytes.size()) {
      throw std::runtime_error("BinaryModel string exceeds compact payload size.");
    }

    model.string_value.assign(reinterpret_cast<const char *>(bytes.data() + offset), string_size);
    offset += string_size;
    models.push_back(std::move(model));
  }

  return models;
}

std::size_t EncodedBinaryModelsSize(const BinaryModelVector &models) {
  std::size_t total_bytes = 0;
  for (const auto &model : models) {
    total_bytes += kRecordHeaderBytes + model.string_value.size();
  }
  return total_bytes;
}

std::uint64_t SampleBinaryModels(const BinaryModelVector &models) {
  if (models.empty()) {
    return 0;
  }

  const std::size_t middle = models.size() / 2;
  const BinaryModel *samples[] = {&models.front(), &models[middle], &models.back()};

  std::uint64_t fingerprint = static_cast<std::uint64_t>(models.size());
  for (const BinaryModel *model : samples) {
    fingerprint = (fingerprint * 1315423911ull) ^ static_cast<std::uint32_t>(model->int_value);
    fingerprint = (fingerprint * 1315423911ull) ^ FloatBits(model->float_value);
    fingerprint = (fingerprint * 1315423911ull) ^ static_cast<std::uint64_t>(model->string_value.size());
    for (unsigned char ch : model->string_value) {
      fingerprint = (fingerprint * 1099511628211ull) ^ ch;
    }
  }

  return fingerprint;
}

} // namespace ffibb
