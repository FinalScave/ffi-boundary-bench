# Emscripten Benchmark

This runner builds the shared C API into an Emscripten module and measures JavaScript boundary paths from Node.js.

## Build

From the repository root:

```powershell
emcmake cmake -S . -B build/emscripten -DCMAKE_BUILD_TYPE=Release -DFFIBB_BUILD_SHARED=OFF -DFFIBB_BUILD_NATIVE_BENCH=OFF
cmake --build build/emscripten --target ffibb_wasm_bench --config Release
```

## Run

```powershell
node platform/Emscripten/src/node_main.js --module build/emscripten/bin/ffibb_wasm_bench.js
```

Optional JSON output:

```powershell
node platform/Emscripten/src/node_main.js --module build/emscripten/bin/ffibb_wasm_bench.js --json build/emscripten/results.json
```

## Bindings

- `uint8_array`: JavaScript encodes into a `Uint8Array`, then copies bytes into wasm memory before calling the C API.
- `wasm_heap`: JavaScript encodes directly into wasm linear memory and calls the C API with that pointer.

For C API encode paths, `uint8_array` copies owned wasm bytes into a JavaScript `Uint8Array` before decoding, while `wasm_heap` decodes from a wasm heap view.
