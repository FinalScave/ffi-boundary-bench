const CASES = [
  { name: "10 KiB", targetBytes: 10 * 1024, iterations: 5000 },
  { name: "200 KiB", targetBytes: 200 * 1024, iterations: 1000 },
  { name: "1 MiB", targetBytes: 1024 * 1024, iterations: 200 },
];

function formatSize(bytes) {
  const kib = 1024;
  const mib = 1024 * 1024;
  if (bytes >= mib) {
    return `${(bytes / mib).toFixed(2)} MiB`;
  }
  if (bytes >= kib) {
    return `${(bytes / kib).toFixed(2)} KiB`;
  }
  return `${bytes} B`;
}

function elementCountForBytes(byteCount) {
  if (byteCount % 4 !== 0) {
    throw new Error("Byte count must align with uint32 elements.");
  }
  return byteCount / 4;
}

module.exports = {
  CASES,
  elementCountForBytes,
  formatSize,
};
