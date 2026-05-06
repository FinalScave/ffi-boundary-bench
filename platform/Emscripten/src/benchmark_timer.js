const { performance } = require("node:perf_hooks");

const WARMUP_ROUNDS = 10;

function measure(iterations, fn) {
  for (let index = 0; index < WARMUP_ROUNDS; index += 1) {
    fn();
  }

  let totalMs = 0;
  let minMs = Number.POSITIVE_INFINITY;
  let maxMs = 0;

  for (let index = 0; index < iterations; index += 1) {
    const begin = performance.now();
    fn();
    const elapsedMs = performance.now() - begin;
    totalMs += elapsedMs;
    minMs = Math.min(minMs, elapsedMs);
    maxMs = Math.max(maxMs, elapsedMs);
  }

  return {
    averageMs: totalMs / iterations,
    minMs,
    maxMs,
  };
}

module.exports = {
  measure,
};
