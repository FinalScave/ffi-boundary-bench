#include "ffibb/ffibb.h"

#include <emscripten/emscripten.h>

extern "C" {

EMSCRIPTEN_KEEPALIVE
size_t ffibb_wasm_owned_bytes_struct_size() {
  return sizeof(ffibb_owned_bytes);
}

EMSCRIPTEN_KEEPALIVE
uint8_t *ffibb_wasm_owned_bytes_data(ffibb_owned_bytes *bytes) {
  if (bytes == nullptr) {
    return nullptr;
  }

  return bytes->data;
}

EMSCRIPTEN_KEEPALIVE
size_t ffibb_wasm_owned_bytes_size(const ffibb_owned_bytes *bytes) {
  if (bytes == nullptr) {
    return 0;
  }

  return bytes->size;
}

EMSCRIPTEN_KEEPALIVE
void ffibb_wasm_free_bytes(ffibb_owned_bytes *bytes) {
  ffibb_free_bytes(bytes);
}

EMSCRIPTEN_KEEPALIVE
ffibb_status ffibb_wasm_process_u32_bytes(
    const uint8_t *data,
    size_t size,
    uint64_t *out_fingerprint) {
  return ffibb_process_u32_bytes(data, size, out_fingerprint);
}

EMSCRIPTEN_KEEPALIVE
ffibb_status ffibb_wasm_process_binary_model_bytes(
    const uint8_t *data,
    size_t size,
    size_t *out_count,
    uint64_t *out_fingerprint) {
  return ffibb_process_binary_model_bytes(data, size, out_count, out_fingerprint);
}

EMSCRIPTEN_KEEPALIVE
ffibb_status ffibb_wasm_build_sample_u32_bytes(
    size_t target_bytes,
    ffibb_owned_bytes *out_bytes,
    uint64_t *out_fingerprint) {
  return ffibb_build_sample_u32_bytes(target_bytes, out_bytes, out_fingerprint);
}

EMSCRIPTEN_KEEPALIVE
ffibb_status ffibb_wasm_build_sample_binary_model_bytes(
    size_t target_bytes,
    ffibb_owned_bytes *out_bytes,
    size_t *out_count,
    uint64_t *out_fingerprint) {
  return ffibb_build_sample_binary_model_bytes(target_bytes, out_bytes, out_count, out_fingerprint);
}

}
