#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  if defined(FFIBB_BUILD_SHARED)
#    define FFIBB_EXPORT __declspec(dllexport)
#  else
#    define FFIBB_EXPORT __declspec(dllimport)
#  endif
#else
#  define FFIBB_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef enum ffibb_status {
  FFIBB_STATUS_OK = 0,
  FFIBB_STATUS_INVALID_ARGUMENT = 1,
  FFIBB_STATUS_ALLOCATION_FAILED = 2,
  FFIBB_STATUS_DECODE_ERROR = 3
} ffibb_status;

typedef struct ffibb_owned_bytes {
  uint8_t *data;
  size_t size;
} ffibb_owned_bytes;

FFIBB_EXPORT ffibb_status ffibb_build_sample_u32_bytes(
  size_t target_bytes,
  ffibb_owned_bytes *out_bytes,
  uint64_t *out_fingerprint);
FFIBB_EXPORT ffibb_status ffibb_process_u32_bytes(
    const uint8_t *data,
    size_t size,
    uint64_t *out_fingerprint);
FFIBB_EXPORT ffibb_status ffibb_process_binary_model_bytes(
    const uint8_t *data,
    size_t size,
    size_t *out_count,
    uint64_t *out_fingerprint);
FFIBB_EXPORT ffibb_status ffibb_build_sample_binary_model_bytes(
  size_t target_bytes,
    ffibb_owned_bytes *out_bytes,
    size_t *out_count,
    uint64_t *out_fingerprint);
FFIBB_EXPORT void ffibb_free_bytes(ffibb_owned_bytes *bytes);
FFIBB_EXPORT const char *ffibb_status_string(ffibb_status status);

#ifdef __cplusplus
}
#endif
