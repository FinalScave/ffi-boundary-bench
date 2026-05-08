# JVM Wasm Benchmark

This runner loads the standalone Emscripten C ABI wasm module in a JVM WebAssembly runtime and measures Java-to-wasm boundary paths.

The Gradle project targets a Java 25 toolchain. If Gradle cannot auto-detect Java 25, pass `-Porg.gradle.java.installations.paths=<jdk-25-path>` before the task name.

## Build Wasm

From the repository root:

```powershell
emcmake cmake -S . -B build/emscripten-standalone -DCMAKE_BUILD_TYPE=Release -DFFIBB_BUILD_SHARED=OFF -DFFIBB_BUILD_NATIVE_BENCH=OFF -DFFIBB_WASM_FLAVOR=standalone
cmake --build build/emscripten-standalone --target ffibb_wasm_cabi --config Release
```

## Run

The default JVM wasm runtime is GraalWasm:

```powershell
java -classpath ../Java22/gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain run --args="--wasm ../../build/emscripten-standalone/bin/ffibb_wasm_cabi.wasm"
```

Run the same benchmark with Chicory:

```powershell
java -classpath ../Java22/gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain run --args="--runtime chicory --wasm ../../build/emscripten-standalone/bin/ffibb_wasm_cabi.wasm"
```

Optional JSON output:

```powershell
java -classpath ../Java22/gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain run --args="--runtime chicory --wasm ../../build/emscripten-standalone/bin/ffibb_wasm_cabi.wasm --json ../../build/emscripten-standalone/jvm-wasm-chicory-results.json"
```

The runtime can also be set with `-Dffibb.wasm.runtime=chicory` or `FFIBB_WASM_RUNTIME=chicory`.

## Bindings

- `byte_array`: Java encodes into a `byte[]`, then copies bytes into wasm memory before calling the wasm C API.
- `wasm_memory`: Java encodes directly into wasm linear memory and calls the wasm C API with that pointer.
