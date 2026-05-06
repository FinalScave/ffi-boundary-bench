#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
PACKAGE_DIR="${ROOT_DIR}/platform/Apple/Packages/FFIBBKit"

TARGET="${1:-all}"
CONFIGURATION="${2:-Release}"

usage() {
  cat >&2 <<USAGE
Usage: $(basename "$0") [macos|ios|all] [Release|Debug]

  macos  Build macOS xcframework (slice: macos-arm64_x86_64)
  ios    Build iOS xcframework (slices: ios-arm64, ios-arm64-simulator)
  all    Build both (default)

  Configuration: Release (default) or Debug.
USAGE
}

write_module_headers() {
  local headers_dir="$1"
  rm -rf "${headers_dir}"
  mkdir -p "${headers_dir}/ffibb"
  cp "${ROOT_DIR}/include/ffibb/ffibb.h" "${headers_dir}/ffibb/ffibb.h"
  cat > "${headers_dir}/module.modulemap" <<'MODULEMAP'
module FFIBB {
  header "ffibb/ffibb.h"
  export *
}
MODULEMAP
}

build_macos() {
  local vendor_dir="${PACKAGE_DIR}/Vendor/macOS"
  local build_root="${ROOT_DIR}/build/macos-xcframework"
  local build_dir="${build_root}/${CONFIGURATION}"
  local headers_dir="${build_root}/Headers"
  local output_path="${vendor_dir}/FFIBB.xcframework"

  rm -rf "${output_path}"
  mkdir -p "${vendor_dir}"
  write_module_headers "${headers_dir}"

  cmake -S "${ROOT_DIR}" -B "${build_dir}" \
    -DCMAKE_BUILD_TYPE="${CONFIGURATION}" \
    -DFFIBB_BUILD_SHARED=ON \
    -DFFIBB_BUILD_NATIVE_BENCH=OFF \
    -DFFIBB_BUILD_WASM_BENCH=OFF \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 \
    "-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64"

  cmake --build "${build_dir}" --target ffibb_capi --config "${CONFIGURATION}"

  local lib_path="${build_dir}/lib/libffibb.dylib"
  if [[ ! -f "${lib_path}" ]]; then
    echo "Expected library was not built: ${lib_path}" >&2
    exit 1
  fi
  install_name_tool -id "@rpath/libffibb.dylib" "${lib_path}"

  xcodebuild -create-xcframework \
    -library "${lib_path}" \
    -headers "${headers_dir}" \
    -output "${output_path}"

  echo "Built ${output_path}"
}

build_ios_slice() {
  local sysroot="$1"
  local build_dir="$2"
  local deployment_target="15.0"

  cmake -S "${ROOT_DIR}" -B "${build_dir}" \
    -DCMAKE_BUILD_TYPE="${CONFIGURATION}" \
    -DFFIBB_BUILD_SHARED=ON \
    -DFFIBB_BUILD_NATIVE_BENCH=OFF \
    -DFFIBB_BUILD_WASM_BENCH=OFF \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="${sysroot}" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="${deployment_target}" \
    -DCMAKE_OSX_ARCHITECTURES=arm64

  cmake --build "${build_dir}" --target ffibb_capi --config "${CONFIGURATION}"
}

build_ios() {
  local vendor_dir="${PACKAGE_DIR}/Vendor/iOS"
  local build_root="${ROOT_DIR}/build/ios-xcframework"
  local device_build_dir="${build_root}/iphoneos-${CONFIGURATION}"
  local sim_build_dir="${build_root}/iphonesimulator-${CONFIGURATION}"
  local headers_dir="${build_root}/Headers"
  local output_path="${vendor_dir}/FFIBB.xcframework"

  rm -rf "${output_path}"
  mkdir -p "${vendor_dir}"
  write_module_headers "${headers_dir}"

  build_ios_slice "iphoneos" "${device_build_dir}"
  build_ios_slice "iphonesimulator" "${sim_build_dir}"

  local device_lib="${device_build_dir}/lib/libffibb.dylib"
  local sim_lib="${sim_build_dir}/lib/libffibb.dylib"

  for lib in "${device_lib}" "${sim_lib}"; do
    if [[ ! -f "${lib}" ]]; then
      echo "Expected library was not built: ${lib}" >&2
      exit 1
    fi
    install_name_tool -id "@rpath/libffibb.dylib" "${lib}"
  done

  xcodebuild -create-xcframework \
    -library "${device_lib}" \
    -headers "${headers_dir}" \
    -library "${sim_lib}" \
    -headers "${headers_dir}" \
    -output "${output_path}"

  echo "Built ${output_path}"
}

case "${TARGET}" in
  macos)
    build_macos
    ;;
  ios)
    build_ios
    ;;
  all)
    build_macos
    build_ios
    ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    echo "Unknown target: ${TARGET}" >&2
    usage
    exit 1
    ;;
esac
