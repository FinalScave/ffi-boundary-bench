cmake_minimum_required(VERSION 3.22)
project(ffi_binary_bench VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if (NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif ()

set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(FFIBB_BUILD_SHARED "Build the shared C API library" ON)
option(FFIBB_BUILD_NATIVE_BENCH "Build the native benchmark runner" ON)
option(FFIBB_BUILD_WASM_BENCH "Build the WebAssembly benchmark target when using Emscripten" ON)
set(FFIBB_WASM_FLAVOR "node" CACHE STRING "WebAssembly benchmark flavor when using Emscripten: node or standalone")
set_property(CACHE FFIBB_WASM_FLAVOR PROPERTY STRINGS node standalone)

function(ffibb_apply_warnings target_name)
  if (MSVC)
    target_compile_options(${target_name} PRIVATE /W4 /permissive- /Zc:__cplusplus)
  else ()
    target_compile_options(${target_name} PRIVATE -Wall -Wextra -Wpedantic)
  endif ()
endfunction()

set(FFIBB_PUBLIC_HEADERS
    include/ffibb/ffibb.h
)

set(FFIBB_CORE_SOURCES
    src/core/benchmark_data.cpp
    src/core/binary_model_codec.cpp
    src/core/compact_u32_codec.cpp
)

add_library(ffibb_core STATIC ${FFIBB_CORE_SOURCES})
target_include_directories(ffibb_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

ffibb_apply_warnings(ffibb_core)

set(FFIBB_PLATFORM_SOURCES)
if (ANDROID)
  list(APPEND FFIBB_PLATFORM_SOURCES platform/Android/app/src/main/cpp/jni_bridge.cpp)
  target_compile_definitions(ffibb_core PUBLIC FFIBB_PLATFORM_ANDROID=1)
elseif (OHOS)
  list(APPEND FFIBB_PLATFORM_SOURCES platform/OHOS/entry/src/main/cpp/napi_init.cpp)
  target_compile_definitions(ffibb_core PUBLIC FFIBB_PLATFORM_OHOS=1)
elseif (EMSCRIPTEN)
  target_compile_definitions(ffibb_core PUBLIC FFIBB_PLATFORM_WASM=1)
elseif (APPLE)
  target_compile_definitions(ffibb_core PUBLIC FFIBB_PLATFORM_APPLE=1)
elseif (WIN32)
  target_compile_definitions(ffibb_core PUBLIC FFIBB_PLATFORM_WINDOWS=1)
elseif (UNIX)
  target_compile_definitions(ffibb_core PUBLIC FFIBB_PLATFORM_LINUX=1)
endif ()

if (FFIBB_BUILD_SHARED)
  add_library(ffibb_capi SHARED src/c_api/ffibb_c_api.cpp ${FFIBB_PLATFORM_SOURCES})
  target_include_directories(ffibb_capi
      PUBLIC
          ${CMAKE_CURRENT_SOURCE_DIR}/include
      PRIVATE
          ${CMAKE_CURRENT_SOURCE_DIR}/src
  )
  target_link_libraries(ffibb_capi PRIVATE ffibb_core)
  target_compile_definitions(ffibb_capi PRIVATE FFIBB_BUILD_SHARED=1)
  set_target_properties(ffibb_capi PROPERTIES
      OUTPUT_NAME ffibb
      PUBLIC_HEADER "${FFIBB_PUBLIC_HEADERS}"
  )

  ffibb_apply_warnings(ffibb_capi)

  if (ANDROID)
    find_library(log-lib log)
    if (log-lib)
      target_link_libraries(ffibb_capi PRIVATE ${log-lib})
    endif ()
  elseif (OHOS)
    target_link_libraries(ffibb_capi PRIVATE ace_napi.z hilog_ndk.z)
  endif ()
endif ()

if (FFIBB_BUILD_NATIVE_BENCH)
  add_executable(ffibb_native_bench src/runner/native_benchmark_main.cpp)
  target_include_directories(ffibb_native_bench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
  target_link_libraries(ffibb_native_bench PRIVATE ffibb_core)
  if (FFIBB_BUILD_SHARED)
    target_link_libraries(ffibb_native_bench PRIVATE ffibb_capi)
  endif ()

  ffibb_apply_warnings(ffibb_native_bench)
endif ()

if (EMSCRIPTEN AND FFIBB_BUILD_WASM_BENCH)
  string(TOLOWER "${FFIBB_WASM_FLAVOR}" FFIBB_WASM_FLAVOR_NORMALIZED)
  if ("${FFIBB_WASM_FLAVOR_NORMALIZED}" STREQUAL "node")
    set(FFIBB_WASM_TARGET ffibb_wasm_bench)
  elseif ("${FFIBB_WASM_FLAVOR_NORMALIZED}" STREQUAL "standalone")
    set(FFIBB_WASM_TARGET ffibb_wasm_cabi)
  else ()
    message(FATAL_ERROR "FFIBB_WASM_FLAVOR must be node or standalone.")
  endif ()

  add_executable(${FFIBB_WASM_TARGET}
      platform/Emscripten/ffibb_wasm_bindings.cpp
      src/c_api/ffibb_c_api.cpp
  )
  target_link_libraries(${FFIBB_WASM_TARGET} PRIVATE ffibb_core)
  target_include_directories(${FFIBB_WASM_TARGET} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
  ffibb_apply_warnings(${FFIBB_WASM_TARGET})

  if ("${FFIBB_WASM_FLAVOR_NORMALIZED}" STREQUAL "node")
    target_link_options(${FFIBB_WASM_TARGET} PRIVATE
        "-sWASM=1"
        "-sALLOW_MEMORY_GROWTH=1"
        "-sMODULARIZE=1"
        "-sEXPORT_NAME=createFfibbWasmBench"
        "-sENVIRONMENT=web,node"
        "-sEXPORTED_FUNCTIONS=['_malloc','_free','_ffibb_wasm_owned_bytes_struct_size','_ffibb_wasm_owned_bytes_data','_ffibb_wasm_owned_bytes_size','_ffibb_wasm_free_bytes','_ffibb_wasm_process_u32_bytes','_ffibb_wasm_process_binary_model_bytes','_ffibb_wasm_build_sample_u32_bytes','_ffibb_wasm_build_sample_binary_model_bytes']"
        "-sEXPORTED_RUNTIME_METHODS=['HEAPU8','HEAPU32']"
    )
  else ()
    set_target_properties(${FFIBB_WASM_TARGET} PROPERTIES
        OUTPUT_NAME ffibb_wasm_cabi
        SUFFIX ".wasm"
    )
    target_link_options(${FFIBB_WASM_TARGET} PRIVATE
        "-sSTANDALONE_WASM=1"
        "-sWASM=1"
        "-sERROR_ON_UNDEFINED_SYMBOLS=1"
        "--no-entry"
        "-sEXPORTED_FUNCTIONS=['_malloc','_free','_ffibb_wasm_owned_bytes_struct_size','_ffibb_wasm_owned_bytes_data','_ffibb_wasm_owned_bytes_size','_ffibb_wasm_free_bytes','_ffibb_wasm_process_u32_bytes','_ffibb_wasm_process_binary_model_bytes','_ffibb_wasm_build_sample_u32_bytes','_ffibb_wasm_build_sample_binary_model_bytes']"
    )
  endif ()
endif ()

include(GNUInstallDirs)
install(DIRECTORY include/
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}
)

if (FFIBB_BUILD_SHARED)
  install(TARGETS ffibb_capi
      RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
      LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
      ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
      PUBLIC_HEADER DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/ffibb
  )
endif ()
