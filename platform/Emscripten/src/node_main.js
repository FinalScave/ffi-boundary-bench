const fs = require("node:fs");
const { WasmBridge, loadWasmModule } = require("./wasm_bridge");
const { runBenchmark } = require("./benchmark_runner");

function parseArguments(argv) {
  const args = {
    modulePath: null,
    jsonPath: null,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === "--module" && index + 1 < argv.length) {
      args.modulePath = argv[index + 1];
      index += 1;
    } else if (value === "--json" && index + 1 < argv.length) {
      args.jsonPath = argv[index + 1];
      index += 1;
    } else if (value === "--help" || value === "-h") {
      args.help = true;
    } else {
      throw new Error(`Unknown argument: ${value}`);
    }
  }

  return args;
}

function printHelp() {
  console.log("Usage: node platform/Emscripten/src/node_main.js [--module <ffibb_wasm_bench.js>] [--json <path>]");
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  if (args.help) {
    printHelp();
    return 0;
  }

  const loaded = await loadWasmModule(args.modulePath);
  const bridge = new WasmBridge(loaded.module);
  const result = runBenchmark(bridge, loaded.modulePath);
  process.stdout.write(result.report);

  if (args.jsonPath) {
    fs.writeFileSync(args.jsonPath, `${JSON.stringify(result.json, null, 2)}\n`, "utf8");
    console.log(`\nJSON: ${args.jsonPath}`);
  }

  return 0;
}

main().then((exitCode) => {
  process.exitCode = exitCode;
}).catch((error) => {
  console.error(`Benchmark failed: ${error.stack || error.message}`);
  process.exitCode = 1;
});
