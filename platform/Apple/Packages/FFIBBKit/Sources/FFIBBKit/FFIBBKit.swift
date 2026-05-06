import Dispatch
import FFIBB
import Foundation

public struct BinaryModel: Equatable, Sendable {
  public let intValue: Int32
  public let floatValue: Float
  public let stringValue: String

  public init(intValue: Int32, floatValue: Float, stringValue: String) {
    self.intValue = intValue
    self.floatValue = floatValue
    self.stringValue = stringValue
  }
}

public enum FFIBBKitError: Error, CustomStringConvertible {
  case payloadGenerationFailed(String)
  case decodeFailed(String)
  case nativeCallFailed(String)
  case validationFailed(String)

  public var description: String {
    switch self {
    case let .payloadGenerationFailed(message):
      return message
    case let .decodeFailed(message):
      return message
    case let .nativeCallFailed(message):
      return message
    case let .validationFailed(message):
      return message
    }
  }
}

public enum BinaryModelPayloadFactory {
  public static func createModelsForTargetBytes(_ targetBytes: Int) throws -> [BinaryModel] {
    if targetBytes < BinaryModelWireCodec.recordHeaderBytes {
      throw FFIBBKitError.payloadGenerationFailed("Target payload is too small for BinaryModel.")
    }

    var models: [BinaryModel] = []
    var usedBytes = 0
    var index = 0

    while usedBytes < targetBytes {
      let remaining = targetBytes - usedBytes
      if remaining < BinaryModelWireCodec.recordHeaderBytes {
        guard let last = models.popLast() else {
          throw FFIBBKitError.payloadGenerationFailed("BinaryModel payload generation failed.")
        }

        let extended = last.stringValue + buildAsciiString(index: index, targetBytes: remaining)
        models.append(BinaryModel(intValue: last.intValue, floatValue: last.floatValue, stringValue: extended))
        usedBytes += remaining
        break
      }

      let maxStringBytes = remaining - BinaryModelWireCodec.recordHeaderBytes
      var stringBytes = min(maxStringBytes, 12 + (index % 19))
      let tailBytes = remaining - BinaryModelWireCodec.recordHeaderBytes - stringBytes
      if tailBytes > 0 && tailBytes < BinaryModelWireCodec.recordHeaderBytes {
        stringBytes += tailBytes
      }

      models.append(makeBinaryModel(index: index, stringBytes: stringBytes))
      usedBytes += BinaryModelWireCodec.recordHeaderBytes + stringBytes
      index += 1
    }

    if BinaryModelWireCodec.encodedSize(models) != targetBytes {
      throw FFIBBKitError.payloadGenerationFailed("BinaryModel payload generation did not hit the requested size.")
    }

    return models
  }

  private static func makeBinaryModel(index: Int, stringBytes: Int) -> BinaryModel {
    BinaryModel(
      intValue: Int32(1000 + (index * 17)),
      floatValue: Float(0.5) + (Float(index) * Float(0.25)),
      stringValue: buildAsciiString(index: index, targetBytes: stringBytes)
    )
  }

  private static func buildAsciiString(index: Int, targetBytes: Int) -> String {
    var bytes = Array("model_\(index)_".utf8)
    if bytes.count > targetBytes {
      return String(decoding: bytes.prefix(targetBytes), as: UTF8.self)
    }

    bytes.reserveCapacity(targetBytes)
    while bytes.count < targetBytes {
      bytes.append(UInt8(97 + ((index + bytes.count) % 26)))
    }

    return String(decoding: bytes, as: UTF8.self)
  }
}

public enum BinaryModelWireCodec {
  public static let recordHeaderBytes = 4 + 4 + 4

  public static func encode(_ models: [BinaryModel]) -> Data {
    let total = encodedSize(models)
    var data = Data(count: total)
    data.withUnsafeMutableBytes { rawBuf in
      guard let base = rawBuf.baseAddress else {
        return
      }

      var offset = 0
      for model in models {
        writeUInt32LE(UInt32(bitPattern: model.intValue), to: base, offset: offset)
        offset += 4
        writeUInt32LE(model.floatValue.bitPattern, to: base, offset: offset)
        offset += 4

        var stringCopy = model.stringValue
        stringCopy.withUTF8 { src in
          writeUInt32LE(UInt32(src.count), to: base, offset: offset)
          offset += 4
          if let srcBase = src.baseAddress, src.count > 0 {
            memcpy(base.advanced(by: offset), srcBase, src.count)
            offset += src.count
          }
        }
      }
    }

    return data
  }

  public static func decode(_ buffer: UnsafeRawBufferPointer) throws -> [BinaryModel] {
    let size = buffer.count
    if size == 0 {
      return []
    }
    guard let base = buffer.baseAddress else {
      throw FFIBBKitError.decodeFailed("Unexpected empty payload buffer.")
    }

    var models: [BinaryModel] = []
    var offset = 0
    while offset < size {
      guard size - offset >= recordHeaderBytes else {
        throw FFIBBKitError.decodeFailed("Unexpected end of compact byte payload.")
      }

      let intValue = Int32(bitPattern: readUInt32LE(from: base, offset: offset))
      offset += 4
      let floatValue = Float(bitPattern: readUInt32LE(from: base, offset: offset))
      offset += 4
      let stringSize = Int(readUInt32LE(from: base, offset: offset))
      offset += 4

      guard stringSize >= 0, size - offset >= stringSize else {
        throw FFIBBKitError.decodeFailed("String size exceeds compact payload size.")
      }

      let bytePtr = base.advanced(by: offset).assumingMemoryBound(to: UInt8.self)
      let stringBuffer = UnsafeBufferPointer(start: bytePtr, count: stringSize)
      let stringValue = String(decoding: stringBuffer, as: UTF8.self)

      models.append(BinaryModel(intValue: intValue, floatValue: floatValue, stringValue: stringValue))
      offset += stringSize
    }
    return models
  }

  public static func decode(_ data: Data) throws -> [BinaryModel] {
    try data.withUnsafeBytes { try decode($0) }
  }

  public static func encodedSize(_ models: [BinaryModel]) -> Int {
    var totalBytes = 0
    for model in models {
      totalBytes += recordHeaderBytes + model.stringValue.utf8.count
    }
    return totalBytes
  }

  public static func fingerprint(_ models: [BinaryModel]) -> UInt64 {
    guard !models.isEmpty else {
      return 0
    }

    let middle = models.count / 2
    let samples = [models[0], models[middle], models[models.count - 1]]

    var value = UInt64(models.count)
    for model in samples {
      value = (value &* 1_315_423_911) ^ UInt64(UInt32(bitPattern: model.intValue))
      value = (value &* 1_315_423_911) ^ UInt64(model.floatValue.bitPattern)
      value = (value &* 1_315_423_911) ^ UInt64(model.stringValue.utf8.count)
      for byte in model.stringValue.utf8 {
        value = (value &* 1_099_511_628_211) ^ UInt64(byte)
      }
    }

    return value
  }

  @inline(__always)
  private static func writeUInt32LE(_ value: UInt32, to base: UnsafeMutableRawPointer, offset: Int) {
    var le = value.littleEndian
    memcpy(base.advanced(by: offset), &le, 4)
  }

  @inline(__always)
  private static func readUInt32LE(from base: UnsafeRawPointer, offset: Int) -> UInt32 {
    UInt32(littleEndian: base.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
  }
}

public struct BenchmarkMeasurement: Equatable, Sendable {
  public let averageMs: Double
  public let minMs: Double
  public let maxMs: Double

  public init(averageMs: Double, minMs: Double, maxMs: Double) {
    self.averageMs = averageMs
    self.minMs = minMs
    self.maxMs = maxMs
  }
}

public enum BenchmarkTimer {
  public static func measure(
    iterations: Int,
    warmupRounds: Int,
    task: () throws -> UInt64
  ) throws -> BenchmarkMeasurement {
    for _ in 0..<warmupRounds {
      BenchmarkSink.add(try task())
    }

    var totalMs = 0.0
    var minMs = Double.greatestFiniteMagnitude
    var maxMs = 0.0

    for _ in 0..<iterations {
      let start = DispatchTime.now().uptimeNanoseconds
      let sinkValue = try task()
      let end = DispatchTime.now().uptimeNanoseconds
      BenchmarkSink.add(sinkValue)

      let elapsedMs = Double(end - start) / 1_000_000.0
      totalMs += elapsedMs
      minMs = min(minMs, elapsedMs)
      maxMs = max(maxMs, elapsedMs)
    }

    return BenchmarkMeasurement(
      averageMs: totalMs / Double(iterations),
      minMs: minMs,
      maxMs: maxMs
    )
  }
}

public struct BenchmarkCase: Equatable, Sendable {
  public let name: String
  public let targetBytes: Int
  public let iterations: Int

  public init(name: String, targetBytes: Int, iterations: Int) {
    self.name = name
    self.targetBytes = targetBytes
    self.iterations = iterations
  }
}

public enum BenchmarkPayloadFamily: String, Sendable {
  case binaryModel = "BinaryModel"
  case u32 = "u32"
}

public struct BenchmarkRow: Equatable, Sendable {
  public let payloadFamily: BenchmarkPayloadFamily
  public let binding: String
  public let operation: String
  public let caseName: String
  public let targetBytes: Int
  public let iterations: Int
  public let elementCount: Int
  public let measurement: BenchmarkMeasurement

  public init(
    payloadFamily: BenchmarkPayloadFamily,
    binding: String,
    operation: String,
    caseName: String,
    targetBytes: Int,
    iterations: Int,
    elementCount: Int,
    measurement: BenchmarkMeasurement
  ) {
    self.payloadFamily = payloadFamily
    self.binding = binding
    self.operation = operation
    self.caseName = caseName
    self.targetBytes = targetBytes
    self.iterations = iterations
    self.elementCount = elementCount
    self.measurement = measurement
  }
}

public enum BenchmarkSink {
  private static var storage: UInt64 = 0

  public static func add(_ contribution: UInt64) {
    storage &+= contribution
  }

  public static func reset() {
    storage = 0
  }

  public static func value() -> UInt64 {
    storage
  }
}

public enum BenchmarkReportFormatter {
  public static func format(
    cases: [BenchmarkCase],
    modelCounts: [Int],
    u32ElementCounts: [Int],
    rows: [BenchmarkRow]
  ) -> String {
    var report = ""

    let platformName = applePlatformName()
    report += "ffi-binary-bench \(platformName) benchmark\n"
    report += "Payload families: BinaryModel, u32 baseline\n"
    report += "Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes\n"
    report += "Operations:\n"
    report += "  BinaryModel: Array<BinaryModel> -> Data -> C API decode\n"
    report += "  BinaryModel: C API encode -> Data -> Array<BinaryModel>\n"
    report += "  u32: [UInt32] -> Data -> C API decode\n"
    report += "  u32: C API encode -> Data -> [UInt32]\n"
    report += "\nDatasets\n"

    for index in cases.indices {
      let benchmarkCase = cases[index]
      let modelCount = modelCounts[index]
      let u32Count = u32ElementCounts[index]
      report += "  \(padRight(benchmarkCase.name, to: 8)) size=\(padRight(formatSize(benchmarkCase.targetBytes), to: 10)) iterations=\(benchmarkCase.iterations) binary_models=\(modelCount) u32_elements=\(u32Count)\n"
    }

    appendRowsForFamily(into: &report, rows: rows, family: .binaryModel, title: "BinaryModel")
    appendRowsForFamily(into: &report, rows: rows, family: .u32, title: "u32 Baseline")

    report += "\nSink: \(BenchmarkSink.value())\n"
    return report
  }

  private static func appendRowsForFamily(
    into report: inout String,
    rows: [BenchmarkRow],
    family: BenchmarkPayloadFamily,
    title: String
  ) {
    report += "\n\(title)\n"
    report += [
      padRight("Binding", to: 31),
      padRight("Operation", to: 35),
      padRight("Case", to: 12),
      padLeft("Iterations", to: 12),
      padLeft("Elements", to: 12),
      padLeft("Avg (ms)", to: 14),
      padLeft("Min (ms)", to: 14),
      padLeft("Max (ms)", to: 14),
      padLeft("Bytes", to: 14)
    ].joined(separator: " ") + "\n"

    for row in rows where row.payloadFamily == family {
      report += [
        padRight(row.binding, to: 31),
        padRight(row.operation, to: 35),
        padRight(row.caseName, to: 12),
        padLeft(String(row.iterations), to: 12),
        padLeft(String(row.elementCount), to: 12),
        padLeft(formatMilliseconds(row.measurement.averageMs), to: 14),
        padLeft(formatMilliseconds(row.measurement.minMs), to: 14),
        padLeft(formatMilliseconds(row.measurement.maxMs), to: 14),
        padLeft(String(row.targetBytes), to: 14)
      ].joined(separator: " ") + "\n"
    }
  }

  private static func applePlatformName() -> String {
    #if os(iOS)
    return "iOS"
    #elseif os(macOS)
    return "macOS"
    #else
    return "Apple"
    #endif
  }

  private static func formatSize(_ bytes: Int) -> String {
    let kib = 1024.0
    let mib = 1024.0 * 1024.0
    let value = Double(bytes)

    if value >= mib {
      return "\(formatDecimal(value / mib, places: 2)) MiB"
    }
    if value >= kib {
      return "\(formatDecimal(value / kib, places: 2)) KiB"
    }
    return "\(bytes) B"
  }

  private static func formatMilliseconds(_ value: Double) -> String {
    formatDecimal(value, places: 3)
  }

  private static func formatDecimal(_ value: Double, places: Int) -> String {
    String(format: "%.\(places)f", locale: Locale(identifier: "en_US_POSIX"), value)
  }

  private static func padRight(_ value: String, to width: Int) -> String {
    guard value.count < width else {
      return value
    }
    return value + String(repeating: " ", count: width - value.count)
  }

  private static func padLeft(_ value: String, to width: Int) -> String {
    guard value.count < width else {
      return value
    }
    return String(repeating: " ", count: width - value.count) + value
  }
}

public enum AppleBenchmarkRunner {
  private static let binding = "swift_data"
  private static let swiftEncodeToCApiDecode = "swift_encode_to_c_api_decode"
  private static let cApiEncodeToSwiftDecode = "c_api_encode_to_swift_decode"
  private static let cases = [
    BenchmarkCase(name: "10 KiB", targetBytes: 10 * 1024, iterations: 100),
    BenchmarkCase(name: "200 KiB", targetBytes: 200 * 1024, iterations: 30),
    BenchmarkCase(name: "1 MiB", targetBytes: 1024 * 1024, iterations: 10)
  ]
  private static let warmupRounds = 3

  public static func runAll() throws -> String {
    BenchmarkSink.reset()

    var rows: [BenchmarkRow] = []
    var modelCounts: [Int] = []
    var u32ElementCounts: [Int] = []

    for benchmarkCase in cases {
      let source = try BinaryModelPayloadFactory.createModelsForTargetBytes(benchmarkCase.targetBytes)
      modelCounts.append(source.count)

      let u32Source = try U32PayloadFactory.createValuesForTargetBytes(benchmarkCase.targetBytes)
      u32ElementCounts.append(u32Source.count)

      rows.append(try runBinaryModelSwiftToCApi(benchmarkCase: benchmarkCase, source: source))
      rows.append(try runBinaryModelCApiToSwift(benchmarkCase: benchmarkCase))
      rows.append(try runU32SwiftToCApi(benchmarkCase: benchmarkCase, source: u32Source))
      rows.append(try runU32CApiToSwift(benchmarkCase: benchmarkCase))
    }

    return BenchmarkReportFormatter.format(
      cases: cases,
      modelCounts: modelCounts,
      u32ElementCounts: u32ElementCounts,
      rows: rows
    )
  }

  private static func runBinaryModelSwiftToCApi(
    benchmarkCase: BenchmarkCase,
    source: [BinaryModel]
  ) throws -> BenchmarkRow {
    let expectedFingerprint = BinaryModelWireCodec.fingerprint(source)
    let measurement = try BenchmarkTimer.measure(
      iterations: benchmarkCase.iterations,
      warmupRounds: warmupRounds
    ) {
      let bytes = BinaryModelWireCodec.encode(source)
      let result = try BinaryModelCApi.processBinaryModelBytes(bytes)
      guard result.count == source.count && result.fingerprint == expectedFingerprint else {
        throw FFIBBKitError.validationFailed("C API decode produced an unexpected fingerprint.")
      }
      return result.fingerprint
    }

    return BenchmarkRow(
      payloadFamily: .binaryModel,
      binding: binding,
      operation: swiftEncodeToCApiDecode,
      caseName: benchmarkCase.name,
      targetBytes: benchmarkCase.targetBytes,
      iterations: benchmarkCase.iterations,
      elementCount: source.count,
      measurement: measurement
    )
  }

  private static func runBinaryModelCApiToSwift(benchmarkCase: BenchmarkCase) throws -> BenchmarkRow {
    var modelCount = 0
    let measurement = try BenchmarkTimer.measure(
      iterations: benchmarkCase.iterations,
      warmupRounds: warmupRounds
    ) {
      try BinaryModelCApi.withBuiltSampleBinaryModelBytes(
        targetBytes: benchmarkCase.targetBytes
      ) { buffer, apiCount, apiFingerprint in
        let models = try BinaryModelWireCodec.decode(buffer)
        let fingerprint = BinaryModelWireCodec.fingerprint(models)
        modelCount = models.count

        guard apiCount == models.count && apiFingerprint == fingerprint else {
          throw FFIBBKitError.validationFailed("Swift decode produced an unexpected fingerprint.")
        }

        return fingerprint
      }
    }

    return BenchmarkRow(
      payloadFamily: .binaryModel,
      binding: binding,
      operation: cApiEncodeToSwiftDecode,
      caseName: benchmarkCase.name,
      targetBytes: benchmarkCase.targetBytes,
      iterations: benchmarkCase.iterations,
      elementCount: modelCount,
      measurement: measurement
    )
  }

  private static func runU32SwiftToCApi(
    benchmarkCase: BenchmarkCase,
    source: [UInt32]
  ) throws -> BenchmarkRow {
    let expectedFingerprint = U32PayloadCodec.fingerprint(source)
    let measurement = try BenchmarkTimer.measure(
      iterations: benchmarkCase.iterations,
      warmupRounds: warmupRounds
    ) {
      let bytes = U32PayloadCodec.encode(source)
      let fingerprint = try U32CApi.processU32Bytes(bytes)
      guard fingerprint == expectedFingerprint else {
        throw FFIBBKitError.validationFailed("C API u32 decode produced an unexpected fingerprint.")
      }
      return fingerprint
    }

    return BenchmarkRow(
      payloadFamily: .u32,
      binding: binding,
      operation: swiftEncodeToCApiDecode,
      caseName: benchmarkCase.name,
      targetBytes: benchmarkCase.targetBytes,
      iterations: benchmarkCase.iterations,
      elementCount: source.count,
      measurement: measurement
    )
  }

  private static func runU32CApiToSwift(benchmarkCase: BenchmarkCase) throws -> BenchmarkRow {
    var elementCount = 0
    let measurement = try BenchmarkTimer.measure(
      iterations: benchmarkCase.iterations,
      warmupRounds: warmupRounds
    ) {
      try U32CApi.withBuiltSampleU32Bytes(
        targetBytes: benchmarkCase.targetBytes
      ) { buffer, apiFingerprint in
        let values = try U32PayloadCodec.decode(buffer)
        let fingerprint = U32PayloadCodec.fingerprint(values)
        elementCount = values.count

        guard apiFingerprint == fingerprint else {
          throw FFIBBKitError.validationFailed("Swift u32 decode produced an unexpected fingerprint.")
        }

        return fingerprint
      }
    }

    return BenchmarkRow(
      payloadFamily: .u32,
      binding: binding,
      operation: cApiEncodeToSwiftDecode,
      caseName: benchmarkCase.name,
      targetBytes: benchmarkCase.targetBytes,
      iterations: benchmarkCase.iterations,
      elementCount: elementCount,
      measurement: measurement
    )
  }
}

public enum BinaryModelCApi {
  public static func processBinaryModelBytes(_ data: Data) throws -> (count: Int, fingerprint: UInt64) {
    try data.withUnsafeBytes { rawBuffer in
      guard let baseAddress = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
        throw FFIBBKitError.nativeCallFailed("Input data is empty.")
      }

      var count = 0
      var fingerprint: UInt64 = 0
      let status = ffibb_process_binary_model_bytes(baseAddress, data.count, &count, &fingerprint)
      try check(status)
      return (count, fingerprint)
    }
  }

  public static func withBuiltSampleBinaryModelBytes<R>(
    targetBytes: Int,
    body: (UnsafeRawBufferPointer, _ count: Int, _ fingerprint: UInt64) throws -> R
  ) throws -> R {
    var ownedBytes = ffibb_owned_bytes()
    var count = 0
    var fingerprint: UInt64 = 0

    let status = ffibb_build_sample_binary_model_bytes(targetBytes, &ownedBytes, &count, &fingerprint)
    try check(status)
    defer { ffibb_free_bytes(&ownedBytes) }

    let buffer: UnsafeRawBufferPointer
    if let ptr = ownedBytes.data, ownedBytes.size > 0 {
      buffer = UnsafeRawBufferPointer(start: UnsafeRawPointer(ptr), count: ownedBytes.size)
    } else {
      buffer = UnsafeRawBufferPointer(start: nil, count: 0)
    }
    return try body(buffer, count, fingerprint)
  }

  public static func statusString(_ status: ffibb_status) -> String {
    guard let pointer = ffibb_status_string(status) else {
      return "unknown"
    }
    return String(cString: pointer)
  }

  private static func check(_ status: ffibb_status) throws {
    if status.rawValue != 0 {
      throw FFIBBKitError.nativeCallFailed(statusString(status))
    }
  }
}

public enum U32PayloadFactory {
  public static func createValuesForTargetBytes(_ targetBytes: Int) throws -> [UInt32] {
    if targetBytes < 0 || targetBytes % MemoryLayout<UInt32>.size != 0 {
      throw FFIBBKitError.payloadGenerationFailed("Byte count must align with uint32 elements.")
    }

    let count = targetBytes / MemoryLayout<UInt32>.size
    var values = [UInt32](repeating: 0, count: count)
    for index in 0..<count {
      let lhs = UInt32(truncatingIfNeeded: UInt64(index) &* 2_654_435_761)
      let rhs = UInt32(truncatingIfNeeded: UInt64(0x9E37_79B9) &+ UInt64(index) &* 17)
      values[index] = lhs ^ rhs
    }
    return values
  }
}

public enum U32PayloadCodec {
  public static func encode(_ values: [UInt32]) -> Data {
    let total = values.count * MemoryLayout<UInt32>.size
    var data = Data(count: total)
    if total == 0 {
      return data
    }

    data.withUnsafeMutableBytes { rawBuf in
      guard let base = rawBuf.baseAddress else {
        return
      }

      var offset = 0
      for value in values {
        var le = value.littleEndian
        memcpy(base.advanced(by: offset), &le, 4)
        offset += 4
      }
    }
    return data
  }

  public static func decode(_ buffer: UnsafeRawBufferPointer) throws -> [UInt32] {
    let size = buffer.count
    if size == 0 {
      return []
    }
    if size % MemoryLayout<UInt32>.size != 0 {
      throw FFIBBKitError.decodeFailed("Compact u32 payload size must align with uint32 elements.")
    }
    guard let base = buffer.baseAddress else {
      throw FFIBBKitError.decodeFailed("Unexpected empty u32 buffer.")
    }

    let count = size / MemoryLayout<UInt32>.size
    var values = [UInt32](repeating: 0, count: count)
    for index in 0..<count {
      let raw = base.loadUnaligned(fromByteOffset: index * 4, as: UInt32.self)
      values[index] = UInt32(littleEndian: raw)
    }
    return values
  }

  public static func decode(_ data: Data) throws -> [UInt32] {
    try data.withUnsafeBytes { try decode($0) }
  }

  public static func fingerprint(_ values: [UInt32]) -> UInt64 {
    guard !values.isEmpty else {
      return 0
    }

    let middle = values.count / 2
    return UInt64(values.count)
      &+ UInt64(values[0])
      &+ UInt64(values[middle])
      &+ UInt64(values[values.count - 1])
  }
}

public enum U32CApi {
  public static func processU32Bytes(_ data: Data) throws -> UInt64 {
    try data.withUnsafeBytes { rawBuffer in
      let baseAddress = rawBuffer.bindMemory(to: UInt8.self).baseAddress
      var fingerprint: UInt64 = 0
      let status = ffibb_process_u32_bytes(baseAddress, data.count, &fingerprint)
      try check(status)
      return fingerprint
    }
  }

  public static func withBuiltSampleU32Bytes<R>(
    targetBytes: Int,
    body: (UnsafeRawBufferPointer, _ fingerprint: UInt64) throws -> R
  ) throws -> R {
    var ownedBytes = ffibb_owned_bytes()
    var fingerprint: UInt64 = 0

    let status = ffibb_build_sample_u32_bytes(targetBytes, &ownedBytes, &fingerprint)
    try check(status)
    defer { ffibb_free_bytes(&ownedBytes) }

    let buffer: UnsafeRawBufferPointer
    if let ptr = ownedBytes.data, ownedBytes.size > 0 {
      buffer = UnsafeRawBufferPointer(start: UnsafeRawPointer(ptr), count: ownedBytes.size)
    } else {
      buffer = UnsafeRawBufferPointer(start: nil, count: 0)
    }
    return try body(buffer, fingerprint)
  }

  private static func check(_ status: ffibb_status) throws {
    if status.rawValue != 0 {
      throw FFIBBKitError.nativeCallFailed(BinaryModelCApi.statusString(status))
    }
  }
}
