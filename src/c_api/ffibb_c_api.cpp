#include "ffibb/ffibb.h"

#include "core/binary_model_codec.h"
#include "core/benchmark_data.h"
#include "core/compact_u32_codec.h"

#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <mutex>
#include <new>
#include <unordered_map>

namespace {

ffibb::ByteBuffer CopyInputBytes(const std::uint8_t *data, std::size_t size) {
  if (size == 0) {
    return {};
  }

  return ffibb::ByteBuffer(data, data + size);
}

ffibb_status CopyOwnedBytes(const ffibb::ByteBuffer &bytes, ffibb_owned_bytes *out_bytes) {
  out_bytes->data = nullptr;
  out_bytes->size = 0;

  if (bytes.empty()) {
    return FFIBB_STATUS_OK;
  }

  auto *buffer = static_cast<std::uint8_t *>(std::malloc(bytes.size()));
  if (buffer == nullptr) {
    return FFIBB_STATUS_ALLOCATION_FAILED;
  }

  std::memcpy(buffer, bytes.data(), bytes.size());
  out_bytes->data = buffer;
  out_bytes->size = bytes.size();
  return FFIBB_STATUS_OK;
}

const ffibb::BinaryModelVector &CachedBinaryModelPayload(std::size_t target_bytes) {
  static std::mutex cache_mutex;
  static std::unordered_map<std::size_t, ffibb::BinaryModelVector> cache;

  std::lock_guard<std::mutex> lock(cache_mutex);
  const auto it = cache.find(target_bytes);
  if (it != cache.end()) {
    return it->second;
  }

  auto inserted = cache.emplace(target_bytes, ffibb::MakeBinaryModelPayload(target_bytes));
  return inserted.first->second;
}

const ffibb::ValueVector &CachedU32Payload(std::size_t target_bytes) {
  static std::mutex cache_mutex;
  static std::unordered_map<std::size_t, ffibb::ValueVector> cache;

  std::lock_guard<std::mutex> lock(cache_mutex);
  const auto it = cache.find(target_bytes);
  if (it != cache.end()) {
    return it->second;
  }

  auto inserted = cache.emplace(target_bytes, ffibb::MakePayload(target_bytes));
  return inserted.first->second;
}

} // namespace

ffibb_status ffibb_build_sample_u32_bytes(
  size_t target_bytes,
    ffibb_owned_bytes *out_bytes,
    uint64_t *out_fingerprint) {
  if (out_bytes == nullptr) {
    return FFIBB_STATUS_INVALID_ARGUMENT;
  }

  out_bytes->data = nullptr;
  out_bytes->size = 0;
  if (out_fingerprint != nullptr) {
    *out_fingerprint = 0;
  }

  try {
    const ffibb::ValueVector &values = CachedU32Payload(target_bytes);
    const ffibb::ByteBuffer bytes = ffibb::EncodeCompactBytes(values);
    const ffibb_status copy_status = CopyOwnedBytes(bytes, out_bytes);
    if (copy_status != FFIBB_STATUS_OK) {
      return copy_status;
    }

    if (out_fingerprint != nullptr) {
      *out_fingerprint = static_cast<std::uint64_t>(ffibb::SampleValues(values));
    }
    return FFIBB_STATUS_OK;
  } catch (const std::bad_alloc &) {
    return FFIBB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return FFIBB_STATUS_INVALID_ARGUMENT;
  }
}

ffibb_status ffibb_process_u32_bytes(
    const uint8_t *data,
    size_t size,
    uint64_t *out_fingerprint) {
  if (data == nullptr && size != 0) {
    return FFIBB_STATUS_INVALID_ARGUMENT;
  }

  try {
    const ffibb::ByteBuffer bytes = CopyInputBytes(data, size);
    const ffibb::ValueVector values = ffibb::DecodeCompactBytes(bytes);
    if (out_fingerprint != nullptr) {
      *out_fingerprint = static_cast<std::uint64_t>(ffibb::SampleValues(values));
    }
    return FFIBB_STATUS_OK;
  } catch (const std::bad_alloc &) {
    return FFIBB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return FFIBB_STATUS_DECODE_ERROR;
  }
}

ffibb_status ffibb_process_binary_model_bytes(
    const uint8_t *data,
    size_t size,
    size_t *out_count,
    uint64_t *out_fingerprint) {
  if (data == nullptr && size != 0) {
    return FFIBB_STATUS_INVALID_ARGUMENT;
  }

  try {
    const ffibb::ByteBuffer bytes = CopyInputBytes(data, size);
    const ffibb::BinaryModelVector models = ffibb::DecodeBinaryModels(bytes);
    if (out_count != nullptr) {
      *out_count = models.size();
    }
    if (out_fingerprint != nullptr) {
      *out_fingerprint = ffibb::SampleBinaryModels(models);
    }
    return FFIBB_STATUS_OK;
  } catch (const std::bad_alloc &) {
    return FFIBB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return FFIBB_STATUS_DECODE_ERROR;
  }
}

ffibb_status ffibb_build_sample_binary_model_bytes(
  size_t target_bytes,
    ffibb_owned_bytes *out_bytes,
    size_t *out_count,
    uint64_t *out_fingerprint) {
  if (out_bytes == nullptr) {
    return FFIBB_STATUS_INVALID_ARGUMENT;
  }

  out_bytes->data = nullptr;
  out_bytes->size = 0;
  if (out_count != nullptr) {
    *out_count = 0;
  }
  if (out_fingerprint != nullptr) {
    *out_fingerprint = 0;
  }

  try {
    const ffibb::BinaryModelVector &models = CachedBinaryModelPayload(target_bytes);
    const ffibb::ByteBuffer bytes = ffibb::EncodeBinaryModels(models);
    const ffibb_status copy_status = CopyOwnedBytes(bytes, out_bytes);
    if (copy_status != FFIBB_STATUS_OK) {
      return copy_status;
    }

    if (out_count != nullptr) {
      *out_count = models.size();
    }
    if (out_fingerprint != nullptr) {
      *out_fingerprint = ffibb::SampleBinaryModels(models);
    }
    return FFIBB_STATUS_OK;
  } catch (const std::bad_alloc &) {
    return FFIBB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return FFIBB_STATUS_INVALID_ARGUMENT;
  }
}

void ffibb_free_bytes(ffibb_owned_bytes *bytes) {
  if (bytes == nullptr) {
    return;
  }

  std::free(bytes->data);
  bytes->data = nullptr;
  bytes->size = 0;
}

const char *ffibb_status_string(ffibb_status status) {
  switch (status) {
    case FFIBB_STATUS_OK:
      return "OK";
    case FFIBB_STATUS_INVALID_ARGUMENT:
      return "INVALID_ARGUMENT";
    case FFIBB_STATUS_ALLOCATION_FAILED:
      return "ALLOCATION_FAILED";
    case FFIBB_STATUS_DECODE_ERROR:
      return "DECODE_ERROR";
    default:
      return "UNKNOWN";
  }
}
