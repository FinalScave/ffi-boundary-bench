#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "ffibb/ffibb.h"

namespace {

void ThrowIllegalArgument(JNIEnv *env, const char *message) {
  jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
  }
}

void ThrowIllegalState(JNIEnv *env, const std::string &message) {
  jclass exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message.c_str());
  }
}

jlong CombineResult(std::size_t count, std::uint64_t fingerprint) {
  return static_cast<jlong>(fingerprint ^ static_cast<std::uint64_t>(count));
}

jlong ProcessBinaryModelBytes(JNIEnv *env, const std::uint8_t *data, std::size_t size) {
  std::size_t decoded_count = 0;
  std::uint64_t fingerprint = 0;
  const ffibb_status status = ffibb_process_binary_model_bytes(data, size, &decoded_count, &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API BinaryModel decode failed: ") + ffibb_status_string(status));
    return 0;
  }

  return CombineResult(decoded_count, fingerprint);
}

jlong ProcessU32Bytes(JNIEnv *env, const std::uint8_t *data, std::size_t size) {
  std::uint64_t fingerprint = 0;
  const ffibb_status status = ffibb_process_u32_bytes(data, size, &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API u32 decode failed: ") + ffibb_status_string(status));
    return 0;
  }

  return CombineResult(size / sizeof(std::uint32_t), fingerprint);
}

std::uint8_t *DirectBufferData(JNIEnv *env, jobject buffer, jint requiredSize) {
  if (buffer == nullptr) {
    ThrowIllegalArgument(env, "Direct ByteBuffer must not be null.");
    return nullptr;
  }
  if (requiredSize < 0) {
    ThrowIllegalArgument(env, "ByteBuffer size must not be negative.");
    return nullptr;
  }

  void *address = env->GetDirectBufferAddress(buffer);
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (address == nullptr || capacity < 0) {
    ThrowIllegalArgument(env, "ByteBuffer must be direct.");
    return nullptr;
  }
  if (capacity < static_cast<jlong>(requiredSize)) {
    ThrowIllegalArgument(env, "Direct ByteBuffer capacity is smaller than the requested payload size.");
    return nullptr;
  }

  return static_cast<std::uint8_t *>(address);
}

jint BuildSampleBinaryModelsToDirectBuffer(JNIEnv *env, jint targetBytes, jobject output) {
  if (targetBytes <= 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return 0;
  }

  std::uint8_t *output_data = DirectBufferData(env, output, targetBytes);
  if (output_data == nullptr) {
    return 0;
  }

  ffibb_owned_bytes encoded{};
  std::size_t model_count = 0;
  std::uint64_t fingerprint = 0;
  const ffibb_status status = ffibb_build_sample_binary_model_bytes(
      static_cast<std::size_t>(targetBytes),
      &encoded,
      &model_count,
      &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API BinaryModel encode failed: ") + ffibb_status_string(status));
    return 0;
  }

  if (encoded.size > static_cast<std::size_t>(targetBytes)) {
    ffibb_free_bytes(&encoded);
    ThrowIllegalState(env, "C API encoded payload exceeds the output Direct ByteBuffer capacity.");
    return 0;
  }

  if (encoded.size != 0) {
    std::memcpy(output_data, encoded.data, encoded.size);
  }
  const jint written = static_cast<jint>(encoded.size);
  ffibb_free_bytes(&encoded);
  static_cast<void>(model_count);
  static_cast<void>(fingerprint);
  return written;
}

jbyteArray BuildSampleU32Bytes(JNIEnv *env, jint targetBytes) {
  if (targetBytes <= 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return nullptr;
  }

  ffibb_owned_bytes encoded{};
  const ffibb_status status = ffibb_build_sample_u32_bytes(static_cast<std::size_t>(targetBytes), &encoded, nullptr);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API u32 encode failed: ") + ffibb_status_string(status));
    return nullptr;
  }

  jbyteArray output = env->NewByteArray(static_cast<jsize>(encoded.size));
  if (output != nullptr && encoded.size != 0) {
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(encoded.size),
                            reinterpret_cast<const jbyte *>(encoded.data));
  }

  ffibb_free_bytes(&encoded);
  if (env->ExceptionCheck()) {
    return nullptr;
  }

  return output;
}

jint BuildSampleU32ToDirectBuffer(JNIEnv *env, jint targetBytes, jobject output) {
  if (targetBytes <= 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return 0;
  }

  std::uint8_t *output_data = DirectBufferData(env, output, targetBytes);
  if (output_data == nullptr) {
    return 0;
  }

  ffibb_owned_bytes encoded{};
  const ffibb_status status = ffibb_build_sample_u32_bytes(static_cast<std::size_t>(targetBytes), &encoded, nullptr);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API u32 encode failed: ") + ffibb_status_string(status));
    return 0;
  }

  if (encoded.size > static_cast<std::size_t>(targetBytes)) {
    ffibb_free_bytes(&encoded);
    ThrowIllegalState(env, "C API encoded u32 payload exceeds the output Direct ByteBuffer capacity.");
    return 0;
  }

  if (encoded.size != 0) {
    std::memcpy(output_data, encoded.data, encoded.size);
  }
  const jint written = static_cast<jint>(encoded.size);
  ffibb_free_bytes(&encoded);
  return written;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeProcessU32(JNIEnv *env, jclass, jbyteArray bytes) {
  if (bytes == nullptr) {
    ThrowIllegalArgument(env, "u32 payload must not be null.");
    return 0;
  }

  const jsize size = env->GetArrayLength(bytes);
  std::vector<jbyte> input(static_cast<std::size_t>(size));
  if (size != 0) {
    env->GetByteArrayRegion(bytes, 0, size, input.data());
    if (env->ExceptionCheck()) {
      return 0;
    }
  }

  const auto *raw_input = size == 0 ? nullptr : reinterpret_cast<const std::uint8_t *>(input.data());
  return ProcessU32Bytes(env, raw_input, static_cast<std::size_t>(size));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeBuildSampleU32(JNIEnv *env, jclass, jint targetBytes) {
  return BuildSampleU32Bytes(env, targetBytes);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeProcessBinaryModels(JNIEnv *env, jclass, jbyteArray bytes) {
  if (bytes == nullptr) {
    ThrowIllegalArgument(env, "BinaryModel payload must not be null.");
    return 0;
  }

  const jsize size = env->GetArrayLength(bytes);
  std::vector<jbyte> input(static_cast<std::size_t>(size));
  if (size != 0) {
    env->GetByteArrayRegion(bytes, 0, size, input.data());
    if (env->ExceptionCheck()) {
      return 0;
    }
  }

  const auto *raw_input =
      size == 0 ? nullptr : reinterpret_cast<const std::uint8_t *>(input.data());
  return ProcessBinaryModelBytes(env, raw_input, static_cast<std::size_t>(size));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeBuildSampleBinaryModels(JNIEnv *env, jclass, jint targetBytes) {
  if (targetBytes <= 0) {
    ThrowIllegalArgument(env, "Target size must be greater than zero.");
    return nullptr;
  }

  ffibb_owned_bytes encoded{};
  std::size_t model_count = 0;
  std::uint64_t fingerprint = 0;
  const ffibb_status status = ffibb_build_sample_binary_model_bytes(
      static_cast<std::size_t>(targetBytes),
      &encoded,
      &model_count,
      &fingerprint);
  if (status != FFIBB_STATUS_OK) {
    ThrowIllegalState(env, std::string("C API BinaryModel encode failed: ") + ffibb_status_string(status));
    return nullptr;
  }

  jbyteArray output = env->NewByteArray(static_cast<jsize>(encoded.size));
  if (output != nullptr && encoded.size != 0) {
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(encoded.size),
                            reinterpret_cast<const jbyte *>(encoded.data));
  }

  ffibb_free_bytes(&encoded);
  if (env->ExceptionCheck()) {
    return nullptr;
  }

  static_cast<void>(model_count);
  static_cast<void>(fingerprint);
  return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeProcessU32FromDirectBuffer(
    JNIEnv *env,
    jclass,
    jobject bytes,
    jint size) {
  std::uint8_t *input_data = DirectBufferData(env, bytes, size);
  if (input_data == nullptr) {
    return 0;
  }

  const auto *raw_input = size == 0 ? nullptr : input_data;
  return ProcessU32Bytes(env, raw_input, static_cast<std::size_t>(size));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeBuildSampleU32ToDirectBuffer(
    JNIEnv *env,
    jclass,
    jint targetBytes,
    jobject output) {
  return BuildSampleU32ToDirectBuffer(env, targetBytes, output);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeProcessBinaryModelsFromDirectBuffer(
    JNIEnv *env,
    jclass,
    jobject bytes,
    jint size) {
  std::uint8_t *input_data = DirectBufferData(env, bytes, size);
  if (input_data == nullptr) {
    return 0;
  }

  const auto *raw_input = size == 0 ? nullptr : input_data;
  return ProcessBinaryModelBytes(env, raw_input, static_cast<std::size_t>(size));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeBuildSampleBinaryModelsToDirectBuffer(
    JNIEnv *env,
    jclass,
    jint targetBytes,
    jobject output) {
  return BuildSampleBinaryModelsToDirectBuffer(env, targetBytes, output);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeProcessU32FromDirectBufferFast(
    JNIEnv *env,
    jclass,
    jobject bytes,
    jint size) {
  std::uint8_t *input_data = DirectBufferData(env, bytes, size);
  if (input_data == nullptr) {
    return 0;
  }

  const auto *raw_input = size == 0 ? nullptr : input_data;
  return ProcessU32Bytes(env, raw_input, static_cast<std::size_t>(size));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeBuildSampleU32ToDirectBufferFast(
    JNIEnv *env,
    jclass,
    jint targetBytes,
    jobject output) {
  return BuildSampleU32ToDirectBuffer(env, targetBytes, output);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeProcessBinaryModelsFromDirectBufferFast(
    JNIEnv *env,
    jclass,
    jobject bytes,
    jint size) {
  std::uint8_t *input_data = DirectBufferData(env, bytes, size);
  if (input_data == nullptr) {
    return 0;
  }

  const auto *raw_input = size == 0 ? nullptr : input_data;
  return ProcessBinaryModelBytes(env, raw_input, static_cast<std::size_t>(size));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ffibb_android_BenchmarkBridge_nativeBuildSampleBinaryModelsToDirectBufferFast(
    JNIEnv *env,
    jclass,
    jint targetBytes,
    jobject output) {
  return BuildSampleBinaryModelsToDirectBuffer(env, targetBytes, output);
}
