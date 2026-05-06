#include "napi/native_api.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>

#include "ffibb/ffibb.h"

namespace {

void ThrowIllegalArgument(napi_env env, const char *message) {
  napi_throw_error(env, "ERR_ILLEGAL_ARGUMENT", message);
}

void ThrowIllegalState(napi_env env, const std::string &message) {
  napi_throw_error(env, "ERR_ILLEGAL_STATE", message.c_str());
}

bool GetUint32Arg(napi_env env, napi_value value, uint32_t *out) {
  napi_status status = napi_get_value_uint32(env, value, out);
  if (status != napi_ok) {
    ThrowIllegalArgument(env, "Argument must be a uint32 number.");
    return false;
  }
  return true;
}

bool GetArrayBufferInfo(
    napi_env env,
    napi_value value,
    void **out_data,
    size_t *out_size) {
  bool is_buffer = false;
  napi_status status = napi_is_arraybuffer(env, value, &is_buffer);
  if (status != napi_ok || !is_buffer) {
    ThrowIllegalArgument(env, "Argument must be an ArrayBuffer.");
    return false;
  }
  status = napi_get_arraybuffer_info(env, value, out_data, out_size);
  if (status != napi_ok) {
    ThrowIllegalState(env, "Failed to read ArrayBuffer storage.");
    return false;
  }
  return true;
}

napi_value CombineResultBigInt(napi_env env, std::size_t count, std::uint64_t fingerprint) {
  std::uint64_t combined = fingerprint ^ static_cast<std::uint64_t>(count);
  napi_value result = nullptr;
  napi_status status = napi_create_bigint_uint64(env, combined, &result);
  if (status != napi_ok) {
    ThrowIllegalState(env, "Failed to create result bigint.");
    return nullptr;
  }
  return result;
}

napi_value ProcessBinaryModelBytes(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {nullptr, nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 2) {
    ThrowIllegalArgument(env, "processBinaryModelBytes(buffer, size) expects 2 arguments.");
    return nullptr;
  }

  void *buffer_data = nullptr;
  size_t buffer_capacity = 0;
  if (!GetArrayBufferInfo(env, argv[0], &buffer_data, &buffer_capacity)) {
    return nullptr;
  }

  uint32_t requested_size = 0;
  if (!GetUint32Arg(env, argv[1], &requested_size)) {
    return nullptr;
  }
  if (requested_size > buffer_capacity) {
    ThrowIllegalArgument(env, "Requested size exceeds ArrayBuffer capacity.");
    return nullptr;
  }

  std::size_t decoded_count = 0;
  std::uint64_t fingerprint = 0;
  const auto *bytes = requested_size == 0
      ? nullptr
      : static_cast<const std::uint8_t *>(buffer_data);
  ffibb_status status = ffibb_process_binary_model_bytes(
      bytes, static_cast<std::size_t>(requested_size), &decoded_count, &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API BinaryModel decode failed: ") + ffibb_status_string(status));
    return nullptr;
  }
  return CombineResultBigInt(env, decoded_count, fingerprint);
}

napi_value BuildSampleBinaryModelBytes(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    ThrowIllegalArgument(env, "buildSampleBinaryModelBytes(targetBytes) expects 1 argument.");
    return nullptr;
  }

  uint32_t target_bytes = 0;
  if (!GetUint32Arg(env, argv[0], &target_bytes)) {
    return nullptr;
  }
  if (target_bytes == 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return nullptr;
  }

  ffibb_owned_bytes encoded{};
  std::size_t model_count = 0;
  std::uint64_t fingerprint = 0;
  ffibb_status status = ffibb_build_sample_binary_model_bytes(
      static_cast<std::size_t>(target_bytes), &encoded, &model_count, &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API BinaryModel encode failed: ") + ffibb_status_string(status));
    return nullptr;
  }

  void *output_data = nullptr;
  napi_value result = nullptr;
  napi_status napi_st = napi_create_arraybuffer(env, encoded.size, &output_data, &result);
  if (napi_st != napi_ok) {
    ffibb_free_bytes(&encoded);
    ThrowIllegalState(env, "Failed to allocate result ArrayBuffer.");
    return nullptr;
  }
  if (encoded.size != 0) {
    std::memcpy(output_data, encoded.data, encoded.size);
  }
  ffibb_free_bytes(&encoded);
  static_cast<void>(model_count);
  static_cast<void>(fingerprint);
  return result;
}

void ExternalEncodedFinalizer(napi_env /*env*/, void *finalize_data, void *finalize_hint) {
  if (finalize_data == nullptr) {
    return;
  }
  ffibb_owned_bytes owned{};
  owned.data = static_cast<std::uint8_t *>(finalize_data);
  owned.size = reinterpret_cast<std::size_t>(finalize_hint);
  ffibb_free_bytes(&owned);
}

napi_value BuildSampleBinaryModelBytesExternal(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    ThrowIllegalArgument(env, "buildSampleBinaryModelBytesExternal(targetBytes) expects 1 argument.");
    return nullptr;
  }

  uint32_t target_bytes = 0;
  if (!GetUint32Arg(env, argv[0], &target_bytes)) {
    return nullptr;
  }
  if (target_bytes == 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return nullptr;
  }

  ffibb_owned_bytes encoded{};
  std::size_t model_count = 0;
  std::uint64_t fingerprint = 0;
  ffibb_status status = ffibb_build_sample_binary_model_bytes(
      static_cast<std::size_t>(target_bytes), &encoded, &model_count, &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API BinaryModel encode failed: ") + ffibb_status_string(status));
    return nullptr;
  }

  napi_value array_buffer = nullptr;
  napi_status napi_st = napi_create_external_arraybuffer(
      env,
      encoded.data,
      encoded.size,
      ExternalEncodedFinalizer,
      reinterpret_cast<void *>(encoded.size),
      &array_buffer);
  if (napi_st != napi_ok) {
    ffibb_free_bytes(&encoded);
    ThrowIllegalState(env, "Failed to wrap encoded bytes as external ArrayBuffer.");
    return nullptr;
  }
  static_cast<void>(model_count);
  static_cast<void>(fingerprint);
  return array_buffer;
}

void PlainFreeFinalizer(napi_env /*env*/, void *finalize_data, void * /*finalize_hint*/) {
  if (finalize_data != nullptr) {
    std::free(finalize_data);
  }
}

napi_value ProcessU32Bytes(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {nullptr, nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 2) {
    ThrowIllegalArgument(env, "processU32Bytes(buffer, size) expects 2 arguments.");
    return nullptr;
  }

  void *buffer_data = nullptr;
  size_t buffer_capacity = 0;
  if (!GetArrayBufferInfo(env, argv[0], &buffer_data, &buffer_capacity)) {
    return nullptr;
  }

  uint32_t requested_size = 0;
  if (!GetUint32Arg(env, argv[1], &requested_size)) {
    return nullptr;
  }
  if (requested_size > buffer_capacity) {
    ThrowIllegalArgument(env, "Requested size exceeds ArrayBuffer capacity.");
    return nullptr;
  }

  std::uint64_t fingerprint = 0;
  const auto *bytes = requested_size == 0
      ? nullptr
      : static_cast<const std::uint8_t *>(buffer_data);
  ffibb_status status = ffibb_process_u32_bytes(
      bytes, static_cast<std::size_t>(requested_size), &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API u32 decode failed: ") + ffibb_status_string(status));
    return nullptr;
  }
  std::size_t element_count = static_cast<std::size_t>(requested_size) / sizeof(std::uint32_t);
  return CombineResultBigInt(env, element_count, fingerprint);
}

napi_value BuildSampleU32Bytes(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    ThrowIllegalArgument(env, "buildSampleU32Bytes(targetBytes) expects 1 argument.");
    return nullptr;
  }

  uint32_t target_bytes = 0;
  if (!GetUint32Arg(env, argv[0], &target_bytes)) {
    return nullptr;
  }
  if (target_bytes == 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return nullptr;
  }

  ffibb_owned_bytes encoded{};
  ffibb_status status = ffibb_build_sample_u32_bytes(
      static_cast<std::size_t>(target_bytes), &encoded, nullptr);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API u32 encode failed: ") + ffibb_status_string(status));
    return nullptr;
  }

  void *output_data = nullptr;
  napi_value result = nullptr;
  napi_status napi_st = napi_create_arraybuffer(env, encoded.size, &output_data, &result);
  if (napi_st != napi_ok) {
    ffibb_free_bytes(&encoded);
    ThrowIllegalState(env, "Failed to allocate result ArrayBuffer.");
    return nullptr;
  }
  if (encoded.size != 0) {
    std::memcpy(output_data, encoded.data, encoded.size);
  }
  ffibb_free_bytes(&encoded);
  return result;
}

napi_value BuildSampleU32BytesExternal(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    ThrowIllegalArgument(env, "buildSampleU32BytesExternal(targetBytes) expects 1 argument.");
    return nullptr;
  }

  uint32_t target_bytes = 0;
  if (!GetUint32Arg(env, argv[0], &target_bytes)) {
    return nullptr;
  }
  if (target_bytes == 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return nullptr;
  }

  ffibb_owned_bytes encoded{};
  ffibb_status status = ffibb_build_sample_u32_bytes(
      static_cast<std::size_t>(target_bytes), &encoded, nullptr);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API u32 encode failed: ") + ffibb_status_string(status));
    return nullptr;
  }

  napi_value array_buffer = nullptr;
  napi_status napi_st = napi_create_external_arraybuffer(
      env,
      encoded.data,
      encoded.size,
      ExternalEncodedFinalizer,
      reinterpret_cast<void *>(encoded.size),
      &array_buffer);
  if (napi_st != napi_ok) {
    ffibb_free_bytes(&encoded);
    ThrowIllegalState(env, "Failed to wrap encoded u32 bytes as external ArrayBuffer.");
    return nullptr;
  }
  return array_buffer;
}

napi_value AllocateExternalBuffer(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    ThrowIllegalArgument(env, "allocateExternalBuffer(size) expects 1 argument.");
    return nullptr;
  }

  uint32_t size = 0;
  if (!GetUint32Arg(env, argv[0], &size)) {
    return nullptr;
  }
  if (size == 0) {
    ThrowIllegalArgument(env, "Buffer size must be greater than zero.");
    return nullptr;
  }

  void *storage = std::malloc(size);
  if (storage == nullptr) {
    ThrowIllegalState(env, "Failed to allocate native external buffer.");
    return nullptr;
  }

  napi_value array_buffer = nullptr;
  napi_status status = napi_create_external_arraybuffer(
      env, storage, size, PlainFreeFinalizer, nullptr, &array_buffer);
  if (status != napi_ok) {
    std::free(storage);
    ThrowIllegalState(env, "Failed to create external ArrayBuffer.");
    return nullptr;
  }
  return array_buffer;
}

napi_value NanoTime(napi_env env, napi_callback_info /*info*/) {
  struct timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  std::uint64_t value = static_cast<std::uint64_t>(ts.tv_sec) * 1000000000ull
      + static_cast<std::uint64_t>(ts.tv_nsec);
  napi_value result = nullptr;
  napi_create_bigint_uint64(env, value, &result);
  return result;
}

}  // namespace

EXTERN_C_START
static napi_value RegisterFfibbModule(napi_env env, napi_value exports) {
  napi_property_descriptor desc[] = {
      {"processBinaryModelBytes", nullptr, ProcessBinaryModelBytes, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"buildSampleBinaryModelBytes", nullptr, BuildSampleBinaryModelBytes, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"buildSampleBinaryModelBytesExternal", nullptr, BuildSampleBinaryModelBytesExternal, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"processU32Bytes", nullptr, ProcessU32Bytes, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"buildSampleU32Bytes", nullptr, BuildSampleU32Bytes, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"buildSampleU32BytesExternal", nullptr, BuildSampleU32BytesExternal, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"allocateExternalBuffer", nullptr, AllocateExternalBuffer, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"nanoTime", nullptr, NanoTime, nullptr, nullptr, nullptr, napi_default, nullptr},
  };
  napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
  return exports;
}
EXTERN_C_END

static napi_module g_ffibb_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = RegisterFfibbModule,
    .nm_modname = "ffibb",
    .nm_priv = ((void *)0),
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterFfibbNapiModule(void) {
  napi_module_register(&g_ffibb_module);
}
