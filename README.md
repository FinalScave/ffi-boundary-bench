# Cross-Runtime FFI Boundary Benchmark

This project measures end-to-end data transfer cost across FFI boundaries between platform runtimes and a shared C API.

The benchmark compares cross-runtime boundary strategies while keeping payload models and wire formats consistent across implementations.

## Benchmark Scope

The project has two benchmark payload families.

### BinaryModel

`BinaryModel` is the main object/list benchmark. Its logical fields are an integer value, a float value, and a UTF-8 string value; each platform names those fields using its own code style.

Operations:

- Platform `List<BinaryModel>` to compact bytes to C API decode/consume
- C API encode to compact bytes to platform `List<BinaryModel>`

Wire format:

- little-endian `int32`
- little-endian `float32`
- little-endian `uint32` string length
- UTF-8 string bytes

### u32 Baseline

`u32` is the raw numeric baseline. It is useful as a control path for lower-level list/array and byte-transfer overhead.

Operations:

- Platform `uint32` list/array to compact bytes to C API decode/consume
- C API encode to compact bytes to platform `uint32` list/array

The u32 baseline should be reported separately from BinaryModel results so object/string encoding costs do not get mixed with raw numeric transfer costs.

## Benchmark Cases

All platform runners should use the same payload sizes:

- `10 KiB`
- `200 KiB`
- `1 MiB`

README result tables should use Release builds. Debug builds are useful for smoke testing but should not be used for performance comparison.

## Platform Coverage

| Platform | Status | Notes |
| --- | --- | --- |
| Native C++ | Implemented | Public C API baseline runner for `BinaryModel` and `u32` |
| Android JNI | Implemented | `byte[]`, direct `ByteBuffer`, direct `ByteBuffer` + `FastNative`; `BinaryModel` and `u32` |
| Java 22 FFM | Implemented | `byte[]`, native `MemorySegment`; `BinaryModel` and `u32` |
| macOS Swift | Implemented | Apple Swift package + xcframework binaryTarget; `BinaryModel` and `u32` |
| iOS Swift | Implemented | Apple Swift package + xcframework binaryTarget (`ios-arm64`, `ios-arm64-simulator`); `BinaryModel` and `u32` |
| HarmonyOS N-API | Implemented | `ArrayBuffer`, external `ArrayBuffer`; `BinaryModel` and `u32` |
| C# P/Invoke | Planned | No benchmark runner yet |
| WebAssembly | Planned | No browser/wasm benchmark runner yet |

## Latest Results

### Native C++ Release

Environment:

- Platform: `native_cpp`
- Build: `Release`
- Device: `MacBook M1 Pro`
- Payload family: `BinaryModel`, `u32`

#### BinaryModel

| Binding | Operation | Case | Iterations | Models | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `c_api` | `binary_model_bytes_to_c_api` | `10 KiB` | 5000 | 312 | 0.007 | 0.006 | 0.078 | 10240 |
| `c_api` | `binary_model_sample_to_owned_bytes` | `10 KiB` | 5000 | 312 | 0.002 | 0.002 | 0.064 | 10240 |
| `c_api` | `binary_model_bytes_to_c_api` | `200 KiB` | 1000 | 6207 | 0.147 | 0.134 | 0.284 | 204800 |
| `c_api` | `binary_model_sample_to_owned_bytes` | `200 KiB` | 1000 | 6207 | 0.049 | 0.044 | 0.097 | 204800 |
| `c_api` | `binary_model_bytes_to_c_api` | `1 MiB` | 200 | 31777 | 0.816 | 0.700 | 1.086 | 1048576 |
| `c_api` | `binary_model_sample_to_owned_bytes` | `1 MiB` | 200 | 31777 | 0.247 | 0.221 | 0.342 | 1048576 |

#### u32 Baseline

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `c_api` | `u32_sample_to_owned_bytes` | `10 KiB` | 5000 | 2560 | 0.000 | 0.000 | 0.008 | 10240 |
| `c_api` | `u32_bytes_to_c_api` | `10 KiB` | 5000 | 2560 | 0.000 | 0.000 | 0.009 | 10240 |
| `c_api` | `u32_sample_to_owned_bytes` | `200 KiB` | 1000 | 51200 | 0.010 | 0.009 | 0.056 | 204800 |
| `c_api` | `u32_bytes_to_c_api` | `200 KiB` | 1000 | 51200 | 0.009 | 0.008 | 0.025 | 204800 |
| `c_api` | `u32_sample_to_owned_bytes` | `1 MiB` | 200 | 262144 | 0.091 | 0.062 | 0.222 | 1048576 |
| `c_api` | `u32_bytes_to_c_api` | `1 MiB` | 200 | 262144 | 0.060 | 0.055 | 0.090 | 1048576 |

### Android Release

Environment:

- Platform: `android`
- Build: `Release`
- Payload family: `BinaryModel`
- Device: `Huawei Mate 30 Pro`
- Android: `12`

#### BinaryModel

| Binding | Operation | Case | Iterations | Models | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `byte_array` | `android_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 0.198 | 0.126 | 0.673 | 10240 |
| `byte_array` | `c_api_encode_to_android_decode` | `10 KiB` | 100 | 312 | 0.259 | 0.106 | 1.108 | 10240 |
| `direct_byte_buffer` | `android_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 0.232 | 0.145 | 5.786 | 10240 |
| `direct_byte_buffer` | `c_api_encode_to_android_decode` | `10 KiB` | 100 | 312 | 0.173 | 0.131 | 0.395 | 10240 |
| `direct_byte_buffer_fast_native` | `android_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 0.197 | 0.118 | 1.043 | 10240 |
| `direct_byte_buffer_fast_native` | `c_api_encode_to_android_decode` | `10 KiB` | 100 | 312 | 0.121 | 0.115 | 0.176 | 10240 |
| `byte_array` | `android_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 1.959 | 1.735 | 2.547 | 204800 |
| `byte_array` | `c_api_encode_to_android_decode` | `200 KiB` | 30 | 6207 | 1.454 | 1.393 | 1.738 | 204800 |
| `direct_byte_buffer` | `android_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 2.366 | 2.157 | 3.558 | 204800 |
| `direct_byte_buffer` | `c_api_encode_to_android_decode` | `200 KiB` | 30 | 6207 | 2.265 | 2.186 | 2.701 | 204800 |
| `direct_byte_buffer_fast_native` | `android_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 2.288 | 2.172 | 2.627 | 204800 |
| `direct_byte_buffer_fast_native` | `c_api_encode_to_android_decode` | `200 KiB` | 30 | 6207 | 2.324 | 2.171 | 3.002 | 204800 |
| `byte_array` | `android_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 10.432 | 9.410 | 16.387 | 1048576 |
| `byte_array` | `c_api_encode_to_android_decode` | `1 MiB` | 10 | 31777 | 8.029 | 7.423 | 9.755 | 1048576 |
| `direct_byte_buffer` | `android_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 13.589 | 13.028 | 14.580 | 1048576 |
| `direct_byte_buffer` | `c_api_encode_to_android_decode` | `1 MiB` | 10 | 31777 | 12.846 | 11.593 | 14.566 | 1048576 |
| `direct_byte_buffer_fast_native` | `android_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 13.975 | 13.008 | 15.388 | 1048576 |
| `direct_byte_buffer_fast_native` | `c_api_encode_to_android_decode` | `1 MiB` | 10 | 31777 | 13.202 | 11.861 | 14.532 | 1048576 |

#### u32 Baseline

No Android Release u32 baseline result has been captured yet.

### Java 22 FFM Release

Environment:

- Platform: `java22_ffm`
- Build: `Release`
- Device: `MacBook M1 Pro`
- Payload family: `BinaryModel`, `u32`

#### BinaryModel

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `byte_array` | `java_encode_to_c_api_decode` | `10 KiB` | 5000 | 312 | 0.021 | 0.013 | 2.039 | 10240 |
| `byte_array` | `c_api_encode_to_java_decode` | `10 KiB` | 5000 | 312 | 0.017 | 0.008 | 1.590 | 10240 |
| `native_memory` | `java_encode_to_c_api_decode` | `10 KiB` | 5000 | 312 | 0.022 | 0.014 | 0.413 | 10240 |
| `native_memory` | `c_api_encode_to_java_decode` | `10 KiB` | 5000 | 312 | 0.025 | 0.012 | 1.523 | 10240 |
| `byte_array` | `java_encode_to_c_api_decode` | `200 KiB` | 1000 | 6207 | 0.278 | 0.253 | 1.990 | 204800 |
| `byte_array` | `c_api_encode_to_java_decode` | `200 KiB` | 1000 | 6207 | 0.164 | 0.128 | 2.040 | 204800 |
| `native_memory` | `java_encode_to_c_api_decode` | `200 KiB` | 1000 | 6207 | 0.314 | 0.280 | 2.093 | 204800 |
| `native_memory` | `c_api_encode_to_java_decode` | `200 KiB` | 1000 | 6207 | 0.294 | 0.241 | 2.070 | 204800 |
| `byte_array` | `java_encode_to_c_api_decode` | `1 MiB` | 200 | 31777 | 1.821 | 1.346 | 3.845 | 1048576 |
| `byte_array` | `c_api_encode_to_java_decode` | `1 MiB` | 200 | 31777 | 0.806 | 0.685 | 2.994 | 1048576 |
| `native_memory` | `java_encode_to_c_api_decode` | `1 MiB` | 200 | 31777 | 1.989 | 1.657 | 3.305 | 1048576 |
| `native_memory` | `c_api_encode_to_java_decode` | `1 MiB` | 200 | 31777 | 1.520 | 1.299 | 3.012 | 1048576 |

#### u32 Baseline

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `byte_array` | `java_encode_to_c_api_decode` | `10 KiB` | 5000 | 2560 | 0.002 | 0.001 | 0.161 | 10240 |
| `byte_array` | `c_api_encode_to_java_decode` | `10 KiB` | 5000 | 2560 | 0.003 | 0.002 | 0.238 | 10240 |
| `native_memory` | `java_encode_to_c_api_decode` | `10 KiB` | 5000 | 2560 | 0.001 | 0.001 | 0.041 | 10240 |
| `native_memory` | `c_api_encode_to_java_decode` | `10 KiB` | 5000 | 2560 | 0.002 | 0.001 | 1.598 | 10240 |
| `byte_array` | `java_encode_to_c_api_decode` | `200 KiB` | 1000 | 51200 | 0.027 | 0.020 | 0.099 | 204800 |
| `byte_array` | `c_api_encode_to_java_decode` | `200 KiB` | 1000 | 51200 | 0.028 | 0.022 | 1.661 | 204800 |
| `native_memory` | `java_encode_to_c_api_decode` | `200 KiB` | 1000 | 51200 | 0.015 | 0.013 | 0.047 | 204800 |
| `native_memory` | `c_api_encode_to_java_decode` | `200 KiB` | 1000 | 51200 | 0.016 | 0.014 | 0.148 | 204800 |
| `byte_array` | `java_encode_to_c_api_decode` | `1 MiB` | 200 | 262144 | 0.174 | 0.125 | 1.568 | 1048576 |
| `byte_array` | `c_api_encode_to_java_decode` | `1 MiB` | 200 | 262144 | 0.350 | 0.116 | 3.537 | 1048576 |
| `native_memory` | `java_encode_to_c_api_decode` | `1 MiB` | 200 | 262144 | 0.113 | 0.081 | 0.316 | 1048576 |
| `native_memory` | `c_api_encode_to_java_decode` | `1 MiB` | 200 | 262144 | 0.131 | 0.072 | 0.316 | 1048576 |

### macOS Swift Release

Environment:

- Platform: `macos_swift`
- Build: `Release`
- Device: `MacBook M1 Pro`
- Payload family: `BinaryModel`, `u32`

#### BinaryModel

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `swift_data` | `swift_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 0.028 | 0.016 | 0.989 | 10240 |
| `swift_data` | `c_api_encode_to_swift_decode` | `10 KiB` | 100 | 312 | 0.043 | 0.030 | 0.105 | 10240 |
| `swift_data` | `swift_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 0.417 | 0.221 | 0.929 | 204800 |
| `swift_data` | `c_api_encode_to_swift_decode` | `200 KiB` | 30 | 6207 | 0.621 | 0.539 | 1.033 | 204800 |
| `swift_data` | `swift_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 1.286 | 1.179 | 1.494 | 1048576 |
| `swift_data` | `c_api_encode_to_swift_decode` | `1 MiB` | 10 | 31777 | 2.147 | 1.970 | 2.296 | 1048576 |

#### u32 Baseline

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `swift_data` | `swift_encode_to_c_api_decode` | `10 KiB` | 100 | 2560 | 0.002 | 0.002 | 0.008 | 10240 |
| `swift_data` | `c_api_encode_to_swift_decode` | `10 KiB` | 100 | 2560 | 0.002 | 0.002 | 0.002 | 10240 |
| `swift_data` | `swift_encode_to_c_api_decode` | `200 KiB` | 30 | 51200 | 0.019 | 0.017 | 0.019 | 204800 |
| `swift_data` | `c_api_encode_to_swift_decode` | `200 KiB` | 30 | 51200 | 0.034 | 0.021 | 0.050 | 204800 |
| `swift_data` | `swift_encode_to_c_api_decode` | `1 MiB` | 10 | 262144 | 0.212 | 0.103 | 0.289 | 1048576 |
| `swift_data` | `c_api_encode_to_swift_decode` | `1 MiB` | 10 | 262144 | 0.145 | 0.090 | 0.206 | 1048576 |

Sink: `3739585894490921904`

### iOS Swift Release

Environment:

- Platform: `ios_swift`
- Build: `Release`
- Payload family: `BinaryModel`, `u32`

#### BinaryModel

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `swift_data` | `swift_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 0.068 | 0.035 | 1.833 | 10240 |
| `swift_data` | `c_api_encode_to_swift_decode` | `10 KiB` | 100 | 312 | 0.080 | 0.036 | 2.912 | 10240 |
| `swift_data` | `swift_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 0.490 | 0.399 | 0.588 | 204800 |
| `swift_data` | `c_api_encode_to_swift_decode` | `200 KiB` | 30 | 6207 | 0.447 | 0.399 | 0.512 | 204800 |
| `swift_data` | `swift_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 1.227 | 1.159 | 1.330 | 1048576 |
| `swift_data` | `c_api_encode_to_swift_decode` | `1 MiB` | 10 | 31777 | 1.312 | 1.244 | 1.370 | 1048576 |

#### u32 Baseline

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `swift_data` | `swift_encode_to_c_api_decode` | `10 KiB` | 100 | 2560 | 0.002 | 0.002 | 0.003 | 10240 |
| `swift_data` | `c_api_encode_to_swift_decode` | `10 KiB` | 100 | 2560 | 0.002 | 0.002 | 0.003 | 10240 |
| `swift_data` | `swift_encode_to_c_api_decode` | `200 KiB` | 30 | 51200 | 0.052 | 0.047 | 0.076 | 204800 |
| `swift_data` | `c_api_encode_to_swift_decode` | `200 KiB` | 30 | 51200 | 0.029 | 0.028 | 0.029 | 204800 |
| `swift_data` | `swift_encode_to_c_api_decode` | `1 MiB` | 10 | 262144 | 0.122 | 0.115 | 0.125 | 1048576 |
| `swift_data` | `c_api_encode_to_swift_decode` | `1 MiB` | 10 | 262144 | 0.060 | 0.057 | 0.064 | 1048576 |

Sink: `3739585894490921904`

### HarmonyOS N-API Release

Environment:

- Platform: `harmonyos_napi`
- Build: `Release`
- Payload family: `BinaryModel`, `u32`

#### BinaryModel

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `array_buffer` | `ohos_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 1.390 | 1.332 | 1.486 | 10240 |
| `array_buffer` | `c_api_encode_to_ohos_decode` | `10 KiB` | 100 | 312 | 0.832 | 0.780 | 0.940 | 10240 |
| `external_array_buffer` | `ohos_encode_to_c_api_decode` | `10 KiB` | 100 | 312 | 1.312 | 1.266 | 1.424 | 10240 |
| `external_array_buffer` | `c_api_encode_to_ohos_decode` | `10 KiB` | 100 | 312 | 0.830 | 0.782 | 1.001 | 10240 |
| `array_buffer` | `ohos_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 28.473 | 27.520 | 30.391 | 204800 |
| `array_buffer` | `c_api_encode_to_ohos_decode` | `200 KiB` | 30 | 6207 | 18.025 | 15.814 | 40.178 | 204800 |
| `external_array_buffer` | `ohos_encode_to_c_api_decode` | `200 KiB` | 30 | 6207 | 27.393 | 25.845 | 39.528 | 204800 |
| `external_array_buffer` | `c_api_encode_to_ohos_decode` | `200 KiB` | 30 | 6207 | 18.332 | 15.649 | 33.623 | 204800 |
| `array_buffer` | `ohos_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 158.465 | 145.570 | 165.166 | 1048576 |
| `array_buffer` | `c_api_encode_to_ohos_decode` | `1 MiB` | 10 | 31777 | 90.167 | 79.222 | 109.387 | 1048576 |
| `external_array_buffer` | `ohos_encode_to_c_api_decode` | `1 MiB` | 10 | 31777 | 137.724 | 135.256 | 142.320 | 1048576 |
| `external_array_buffer` | `c_api_encode_to_ohos_decode` | `1 MiB` | 10 | 31777 | 89.295 | 80.771 | 97.421 | 1048576 |

#### u32 Baseline

| Binding | Operation | Case | Iterations | Elements | Avg (ms) | Min (ms) | Max (ms) | Bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `array_buffer` | `ohos_encode_to_c_api_decode` | `10 KiB` | 100 | 2560 | 0.503 | 0.485 | 0.564 | 10240 |
| `array_buffer` | `c_api_encode_to_ohos_decode` | `10 KiB` | 100 | 2560 | 0.502 | 0.481 | 0.565 | 10240 |
| `external_array_buffer` | `ohos_encode_to_c_api_decode` | `10 KiB` | 100 | 2560 | 0.489 | 0.466 | 0.522 | 10240 |
| `external_array_buffer` | `c_api_encode_to_ohos_decode` | `10 KiB` | 100 | 2560 | 0.500 | 0.477 | 0.563 | 10240 |
| `array_buffer` | `ohos_encode_to_c_api_decode` | `200 KiB` | 30 | 51200 | 9.887 | 9.730 | 10.058 | 204800 |
| `array_buffer` | `c_api_encode_to_ohos_decode` | `200 KiB` | 30 | 51200 | 9.555 | 9.401 | 9.796 | 204800 |
| `external_array_buffer` | `ohos_encode_to_c_api_decode` | `200 KiB` | 30 | 51200 | 9.728 | 9.641 | 9.813 | 204800 |
| `external_array_buffer` | `c_api_encode_to_ohos_decode` | `200 KiB` | 30 | 51200 | 9.626 | 9.351 | 10.573 | 204800 |
| `array_buffer` | `ohos_encode_to_c_api_decode` | `1 MiB` | 10 | 262144 | 49.979 | 49.467 | 50.552 | 1048576 |
| `array_buffer` | `c_api_encode_to_ohos_decode` | `1 MiB` | 10 | 262144 | 61.907 | 48.028 | 162.677 | 1048576 |
| `external_array_buffer` | `ohos_encode_to_c_api_decode` | `1 MiB` | 10 | 262144 | 50.152 | 48.086 | 50.991 | 1048576 |
| `external_array_buffer` | `c_api_encode_to_ohos_decode` | `1 MiB` | 10 | 262144 | 50.477 | 48.945 | 57.444 | 1048576 |

## Result Fields

Each platform runner should produce rows with these logical fields:

- `payload_family`: `BinaryModel` or `u32`
- `binding`
- `operation`
- `case_name`
- `payload_bytes`
- `element_count`
- `iterations`
- `average_ms`
- `min_ms`
- `max_ms`

Platform-specific README tables may omit repeated section-level fields such as `payload_family` when those values are already named by the section.
