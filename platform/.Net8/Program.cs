using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Buffers.Binary;

internal static unsafe class Program {
	private const string PayloadFamilyBinaryModel = "BinaryModel";
	private const string PayloadFamilyU32 = "u32";
	private const string BindingByteArray = "byte_array";
	private const string BindingNativeMemory = "native_memory";
	private const string DotnetEncodeToCApiDecode = "dotnet_encode_to_c_api_decode";
	private const string CApiEncodeToDotnetDecode = "c_api_encode_to_dotnet_decode";
	private const int RecordHeaderBytes = 4 + 4 + 4;
	private const int WarmupRounds = 10;

	private static readonly BenchmarkCase[] Cases =
	{
		new("10 KiB", 10 * 1024, 5000),
		new("200 KiB", 200 * 1024, 1000),
		new("1 MiB", 1024 * 1024, 200),
	};

	private static ulong sink;

	public static int Main(string[] args) {
		try {
			Arguments arguments = Arguments.Parse(args);
			NativeMethods.Load(arguments.LibraryPath);

			List<BenchmarkRow> rows = [];
			List<int> modelCounts = [];
			List<int> u32ElementCounts = [];
			sink = 0;

			foreach (BenchmarkCase benchmarkCase in Cases) {
				List<BinaryModel> source = BinaryModelPayloadFactory.CreateModelsForTargetBytes(benchmarkCase.TargetBytes);
				uint[] u32Source = U32PayloadCodec.CreateValuesForTargetBytes(benchmarkCase.TargetBytes);
				modelCounts.Add(source.Count);
				u32ElementCounts.Add(u32Source.Length);

				rows.Add(RunDotnetToNativeDecodeByteArray(benchmarkCase, source));
				rows.Add(RunNativeToDotnetDecodeByteArray(benchmarkCase));
				rows.Add(RunDotnetToNativeDecodeNativeMemory(benchmarkCase, source));
				rows.Add(RunNativeToDotnetDecodeNativeMemory(benchmarkCase));
				rows.Add(RunDotnetToNativeDecodeU32ByteArray(benchmarkCase, u32Source));
				rows.Add(RunNativeToDotnetDecodeU32ByteArray(benchmarkCase, u32Source));
				rows.Add(RunDotnetToNativeDecodeU32NativeMemory(benchmarkCase, u32Source));
				rows.Add(RunNativeToDotnetDecodeU32NativeMemory(benchmarkCase, u32Source));
			}

			string report = BenchmarkReportFormatter.Format(arguments.LibraryPath, Cases, modelCounts, u32ElementCounts, rows);
			Console.Write(report);

			if (arguments.JsonPath is not null) {
				WriteJsonResults(arguments.JsonPath, rows);
				Console.WriteLine();
				Console.WriteLine("JSON: " + arguments.JsonPath);
			}

			return 0;
		} catch (Exception exception) {
			Console.Error.WriteLine("Benchmark failed: " + exception);
			return 1;
		}
	}

	private static BenchmarkRow RunDotnetToNativeDecodeByteArray(BenchmarkCase benchmarkCase, List<BinaryModel> source) {
		ulong expectedFingerprint = BinaryModelWireCodec.Fingerprint(source);
		nuint expectedCount = (nuint)source.Count;

		byte[] warmupBytes = BinaryModelWireCodec.Encode(source);
		DecodeResult warmupResult;
		fixed (byte* warmupPtr = warmupBytes) {
			warmupResult = NativeMethods.ProcessBinaryModelBytes(warmupPtr, (nuint)warmupBytes.Length);
		}
		VerifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
		Consume(warmupResult.CombinedValue);

		BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
			byte[] bytes = BinaryModelWireCodec.Encode(source);
			fixed (byte* ptr = bytes) {
				DecodeResult result = NativeMethods.ProcessBinaryModelBytes(ptr, (nuint)bytes.Length);
				VerifyDecodeResult(result, expectedCount, expectedFingerprint);
				return result.CombinedValue;
			}
		});

		return new BenchmarkRow(
			PayloadFamilyBinaryModel,
			BindingByteArray,
			DotnetEncodeToCApiDecode,
			benchmarkCase,
			source.Count,
			measurement);
	}

	private static BenchmarkRow RunNativeToDotnetDecodeByteArray(BenchmarkCase benchmarkCase) {
		using EncodedBytes warmupBytes = NativeMethods.BuildSampleBinaryModelBytes((nuint)benchmarkCase.TargetBytes);
		byte[] warmupManagedBytes = CopyNativeToByteArray(warmupBytes);
		List<BinaryModel> warmupModels = BinaryModelWireCodec.Decode(warmupManagedBytes);
		VerifyEncodedModels(warmupBytes, warmupModels);
		Consume(BinaryModelWireCodec.Fingerprint(warmupModels));

		int latestModelCount = warmupModels.Count;
		BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
			using EncodedBytes encodedBytes = NativeMethods.BuildSampleBinaryModelBytes((nuint)benchmarkCase.TargetBytes);
			byte[] managedBytes = CopyNativeToByteArray(encodedBytes);
			List<BinaryModel> models = BinaryModelWireCodec.Decode(managedBytes);
			VerifyEncodedModels(encodedBytes, models);
			latestModelCount = models.Count;
			return BinaryModelWireCodec.Fingerprint(models);
		});

		return new BenchmarkRow(
			PayloadFamilyBinaryModel,
			BindingByteArray,
			CApiEncodeToDotnetDecode,
			benchmarkCase,
			latestModelCount,
			measurement);
	}

	private static BenchmarkRow RunDotnetToNativeDecodeNativeMemory(BenchmarkCase benchmarkCase, List<BinaryModel> source) {
		ulong expectedFingerprint = BinaryModelWireCodec.Fingerprint(source);
		nuint expectedCount = (nuint)source.Count;
		byte* nativeBytes = (byte*)NativeMemory.Alloc((nuint)benchmarkCase.TargetBytes);
		try {
			BinaryModelWireCodec.EncodeToPointer(source, nativeBytes, benchmarkCase.TargetBytes);
			DecodeResult warmupResult = NativeMethods.ProcessBinaryModelBytes(nativeBytes, (nuint)benchmarkCase.TargetBytes);
			VerifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
			Consume(warmupResult.CombinedValue);

			BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
				BinaryModelWireCodec.EncodeToPointer(source, nativeBytes, benchmarkCase.TargetBytes);
				DecodeResult result = NativeMethods.ProcessBinaryModelBytes(nativeBytes, (nuint)benchmarkCase.TargetBytes);
				VerifyDecodeResult(result, expectedCount, expectedFingerprint);
				return result.CombinedValue;
			});

			return new BenchmarkRow(
				PayloadFamilyBinaryModel,
				BindingNativeMemory,
				DotnetEncodeToCApiDecode,
				benchmarkCase,
				source.Count,
				measurement);
		} finally {
			NativeMemory.Free(nativeBytes);
		}
	}

	private static BenchmarkRow RunNativeToDotnetDecodeNativeMemory(BenchmarkCase benchmarkCase) {
		using EncodedBytes warmupBytes = NativeMethods.BuildSampleBinaryModelBytes((nuint)benchmarkCase.TargetBytes);
		List<BinaryModel> warmupModels = BinaryModelWireCodec.Decode(warmupBytes.AsSpan());
		VerifyEncodedModels(warmupBytes, warmupModels);
		Consume(BinaryModelWireCodec.Fingerprint(warmupModels));

		int latestModelCount = warmupModels.Count;
		BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
			using EncodedBytes encodedBytes = NativeMethods.BuildSampleBinaryModelBytes((nuint)benchmarkCase.TargetBytes);
			List<BinaryModel> models = BinaryModelWireCodec.Decode(encodedBytes.AsSpan());
			VerifyEncodedModels(encodedBytes, models);
			latestModelCount = models.Count;
			return BinaryModelWireCodec.Fingerprint(models);
		});

		return new BenchmarkRow(
			PayloadFamilyBinaryModel,
			BindingNativeMemory,
			CApiEncodeToDotnetDecode,
			benchmarkCase,
			latestModelCount,
			measurement);
	}

	private static BenchmarkRow RunDotnetToNativeDecodeU32ByteArray(BenchmarkCase benchmarkCase, uint[] source) {
		ulong expectedFingerprint = U32PayloadCodec.Fingerprint(source);
		nuint expectedCount = (nuint)source.Length;

		byte[] warmupBytes = U32PayloadCodec.Encode(source);
		DecodeResult warmupResult;
		fixed (byte* warmupPtr = warmupBytes) {
			warmupResult = NativeMethods.ProcessU32Bytes(warmupPtr, (nuint)warmupBytes.Length);
		}
		VerifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
		Consume(warmupResult.CombinedValue);

		BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
			byte[] bytes = U32PayloadCodec.Encode(source);
			fixed (byte* ptr = bytes) {
				DecodeResult result = NativeMethods.ProcessU32Bytes(ptr, (nuint)bytes.Length);
				VerifyDecodeResult(result, expectedCount, expectedFingerprint);
				return result.CombinedValue;
			}
		});

		return new BenchmarkRow(
			PayloadFamilyU32,
			BindingByteArray,
			DotnetEncodeToCApiDecode,
			benchmarkCase,
			source.Length,
			measurement);
	}

	private static BenchmarkRow RunNativeToDotnetDecodeU32ByteArray(BenchmarkCase benchmarkCase, uint[] source) {
		ulong expectedFingerprint = U32PayloadCodec.Fingerprint(source);

		using EncodedBytes warmupBytes = NativeMethods.BuildSampleU32Bytes((nuint)benchmarkCase.TargetBytes);
		uint[] warmupValues = U32PayloadCodec.Decode(CopyNativeToByteArray(warmupBytes));
		VerifyDecodedU32Values(warmupValues, source.Length, expectedFingerprint);
		Consume(U32PayloadCodec.Fingerprint(warmupValues));

		BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
			using EncodedBytes encodedBytes = NativeMethods.BuildSampleU32Bytes((nuint)benchmarkCase.TargetBytes);
			uint[] values = U32PayloadCodec.Decode(CopyNativeToByteArray(encodedBytes));
			VerifyDecodedU32Values(values, source.Length, expectedFingerprint);
			return U32PayloadCodec.Fingerprint(values);
		});

		return new BenchmarkRow(
			PayloadFamilyU32,
			BindingByteArray,
			CApiEncodeToDotnetDecode,
			benchmarkCase,
			source.Length,
			measurement);
	}

	private static BenchmarkRow RunDotnetToNativeDecodeU32NativeMemory(BenchmarkCase benchmarkCase, uint[] source) {
		ulong expectedFingerprint = U32PayloadCodec.Fingerprint(source);
		nuint expectedCount = (nuint)source.Length;
		byte* nativeBytes = (byte*)NativeMemory.Alloc((nuint)benchmarkCase.TargetBytes);
		try {
			U32PayloadCodec.EncodeToPointer(source, nativeBytes, benchmarkCase.TargetBytes);
			DecodeResult warmupResult = NativeMethods.ProcessU32Bytes(nativeBytes, (nuint)benchmarkCase.TargetBytes);
			VerifyDecodeResult(warmupResult, expectedCount, expectedFingerprint);
			Consume(warmupResult.CombinedValue);

			BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
				U32PayloadCodec.EncodeToPointer(source, nativeBytes, benchmarkCase.TargetBytes);
				DecodeResult result = NativeMethods.ProcessU32Bytes(nativeBytes, (nuint)benchmarkCase.TargetBytes);
				VerifyDecodeResult(result, expectedCount, expectedFingerprint);
				return result.CombinedValue;
			});

			return new BenchmarkRow(
				PayloadFamilyU32,
				BindingNativeMemory,
				DotnetEncodeToCApiDecode,
				benchmarkCase,
				source.Length,
				measurement);
		} finally {
			NativeMemory.Free(nativeBytes);
		}
	}

	private static BenchmarkRow RunNativeToDotnetDecodeU32NativeMemory(BenchmarkCase benchmarkCase, uint[] source) {
		ulong expectedFingerprint = U32PayloadCodec.Fingerprint(source);

		using EncodedBytes warmupBytes = NativeMethods.BuildSampleU32Bytes((nuint)benchmarkCase.TargetBytes);
		uint[] warmupValues = U32PayloadCodec.Decode(warmupBytes.AsSpan());
		VerifyDecodedU32Values(warmupValues, source.Length, expectedFingerprint);
		Consume(U32PayloadCodec.Fingerprint(warmupValues));

		BenchmarkMeasurement measurement = BenchmarkTimer.Measure(benchmarkCase.Iterations, WarmupRounds, () => {
			using EncodedBytes encodedBytes = NativeMethods.BuildSampleU32Bytes((nuint)benchmarkCase.TargetBytes);
			uint[] values = U32PayloadCodec.Decode(encodedBytes.AsSpan());
			VerifyDecodedU32Values(values, source.Length, expectedFingerprint);
			return U32PayloadCodec.Fingerprint(values);
		});

		return new BenchmarkRow(
			PayloadFamilyU32,
			BindingNativeMemory,
			CApiEncodeToDotnetDecode,
			benchmarkCase,
			source.Length,
			measurement);
	}

	private static void VerifyDecodeResult(DecodeResult result, nuint expectedCount, ulong expectedFingerprint) {
		if (result.Count != expectedCount || result.Fingerprint != expectedFingerprint) {
			throw new InvalidOperationException("C API decode produced an unexpected fingerprint.");
		}
	}

	private static void VerifyEncodedModels(EncodedBytes encodedBytes, List<BinaryModel> models) {
		ulong fingerprint = BinaryModelWireCodec.Fingerprint(models);
		if (encodedBytes.Count != (nuint)models.Count || encodedBytes.Fingerprint != fingerprint) {
			throw new InvalidOperationException(".NET decode produced an unexpected fingerprint.");
		}
	}

	private static void VerifyDecodedU32Values(uint[] values, int expectedCount, ulong expectedFingerprint) {
		if (values.Length != expectedCount || U32PayloadCodec.Fingerprint(values) != expectedFingerprint) {
			throw new InvalidOperationException(".NET u32 decode produced an unexpected fingerprint.");
		}
	}

	private static byte[] CopyNativeToByteArray(EncodedBytes encodedBytes) {
		int size = checked((int)encodedBytes.Size);
		byte[] bytes = new byte[size];
		if (size != 0) {
			Marshal.Copy((IntPtr)encodedBytes.Data, bytes, 0, size);
		}
		return bytes;
	}

	private static void Consume(ulong value) {
		sink += value;
	}

	private static void WriteJsonResults(string path, IReadOnlyList<BenchmarkRow> rows) {
		StringBuilder json = new();
		json.Append("{\n");
		json.Append("  \"rows\": [\n");

		for (int index = 0; index < rows.Count; index++) {
			BenchmarkRow row = rows[index];
			json.Append("    {\n");
			json.Append("      \"payload_family\": \"").Append(EscapeJson(row.PayloadFamily)).Append("\",\n");
			json.Append("      \"binding\": \"").Append(EscapeJson(row.Binding)).Append("\",\n");
			json.Append("      \"operation\": \"").Append(EscapeJson(row.Operation)).Append("\",\n");
			json.Append("      \"case_name\": \"").Append(EscapeJson(row.BenchmarkCase.Name)).Append("\",\n");
			json.Append("      \"payload_bytes\": ").Append(row.BenchmarkCase.TargetBytes).Append(",\n");
			json.Append("      \"element_count\": ").Append(row.ElementCount).Append(",\n");
			json.Append("      \"iterations\": ").Append(row.BenchmarkCase.Iterations).Append(",\n");
			json.Append(CultureInfo.InvariantCulture, $"      \"average_ms\": {row.Measurement.AverageMs:F6},\n");
			json.Append(CultureInfo.InvariantCulture, $"      \"min_ms\": {row.Measurement.MinMs:F6},\n");
			json.Append(CultureInfo.InvariantCulture, $"      \"max_ms\": {row.Measurement.MaxMs:F6}\n");
			json.Append("    }");
			if (index + 1 != rows.Count) {
				json.Append(',');
			}
			json.Append('\n');
		}

		json.Append("  ]\n");
		json.Append("}\n");
		File.WriteAllText(path, json.ToString());
	}

	private static string EscapeJson(string value) {
		return value.Replace("\\", "\\\\", StringComparison.Ordinal)
			.Replace("\"", "\\\"", StringComparison.Ordinal)
			.Replace("\n", "\\n", StringComparison.Ordinal);
	}

	private readonly record struct BenchmarkCase(string Name, int TargetBytes, int Iterations);

	private readonly record struct BenchmarkMeasurement(double AverageMs, double MinMs, double MaxMs);

	private readonly record struct BenchmarkRow(
		string PayloadFamily,
		string Binding,
		string Operation,
		BenchmarkCase BenchmarkCase,
		int ElementCount,
		BenchmarkMeasurement Measurement);

	private readonly record struct BinaryModel(int IntValue, float FloatValue, string StringValue);

	private readonly record struct DecodeResult(nuint Count, ulong Fingerprint) {
		public ulong CombinedValue => Fingerprint ^ Count;
	}

	private sealed class EncodedBytes : IDisposable {
		private NativeMethods.OwnedBytes ownedBytes;
		private bool disposed;

		public EncodedBytes(NativeMethods.OwnedBytes ownedBytes, nuint count, ulong fingerprint) {
			this.ownedBytes = ownedBytes;
			Count = count;
			Fingerprint = fingerprint;
		}

		public byte* Data => ownedBytes.Data;

		public nuint Size => ownedBytes.Size;

		public nuint Count { get; }

		public ulong Fingerprint { get; }

		public ReadOnlySpan<byte> AsSpan() {
			return new ReadOnlySpan<byte>(Data, checked((int)Size));
		}

		public void Dispose() {
			if (disposed) {
				return;
			}

			fixed (NativeMethods.OwnedBytes* ptr = &ownedBytes) {
				NativeMethods.FreeBytes(ptr);
			}
			disposed = true;
		}
	}

	private static class BinaryModelPayloadFactory {
		public static List<BinaryModel> CreateModelsForTargetBytes(int targetBytes) {
			if (targetBytes < RecordHeaderBytes) {
				throw new ArgumentException("Target payload is too small for BinaryModel.");
			}

			List<BinaryModel> models = [];
			int usedBytes = 0;
			int index = 0;

			while (usedBytes < targetBytes) {
				int remaining = targetBytes - usedBytes;
				if (remaining < RecordHeaderBytes) {
					if (models.Count == 0) {
						throw new InvalidOperationException("BinaryModel payload generation failed.");
					}

					BinaryModel last = models[^1];
					models[^1] = last with { StringValue = last.StringValue + BuildAsciiString(index, remaining) };
					usedBytes += remaining;
					break;
				}

				int maxStringBytes = remaining - RecordHeaderBytes;
				int stringBytes = Math.Min(maxStringBytes, 12 + (index % 19));
				int tailBytes = remaining - RecordHeaderBytes - stringBytes;
				if (tailBytes > 0 && tailBytes < RecordHeaderBytes) {
					stringBytes += tailBytes;
				}

				models.Add(MakeBinaryModel(index, stringBytes));
				usedBytes += RecordHeaderBytes + stringBytes;
				index += 1;
			}

			if (BinaryModelWireCodec.EncodedSize(models) != targetBytes) {
				throw new InvalidOperationException("BinaryModel payload generation did not hit the requested size.");
			}

			return models;
		}

		private static BinaryModel MakeBinaryModel(int index, int stringBytes) {
			int intValue = 1000 + (index * 17);
			float floatValue = 0.5f + (index * 0.25f);
			return new BinaryModel(intValue, floatValue, BuildAsciiString(index, stringBytes));
		}

		private static string BuildAsciiString(int index, int targetBytes) {
			StringBuilder builder = new("model_");
			builder.Append(index);
			builder.Append('_');
			if (builder.Length > targetBytes) {
				return builder.ToString(0, targetBytes);
			}

			builder.EnsureCapacity(targetBytes);
			while (builder.Length < targetBytes) {
				builder.Append((char)('a' + ((index + builder.Length) % 26)));
			}

			return builder.ToString();
		}
	}

	private static class BinaryModelWireCodec {
		public static byte[] Encode(IReadOnlyList<BinaryModel> models) {
			byte[] bytes = new byte[EncodedSize(models)];
			EncodeToSpan(models, bytes);
			return bytes;
		}

		public static void EncodeToPointer(IReadOnlyList<BinaryModel> models, byte* output, int size) {
			EncodeToSpan(models, new Span<byte>(output, size));
		}

		public static List<BinaryModel> Decode(ReadOnlySpan<byte> bytes) {
			List<BinaryModel> models = [];
			int offset = 0;

			while (offset < bytes.Length) {
				if (bytes.Length - offset < RecordHeaderBytes) {
					throw new ArgumentException("Unexpected end of compact byte payload.");
				}

				int intValue = BinaryPrimitives.ReadInt32LittleEndian(bytes.Slice(offset, 4));
				offset += 4;
				float floatValue = BitConverter.Int32BitsToSingle(BinaryPrimitives.ReadInt32LittleEndian(bytes.Slice(offset, 4)));
				offset += 4;
				int stringSize = checked((int)BinaryPrimitives.ReadUInt32LittleEndian(bytes.Slice(offset, 4)));
				offset += 4;

				if (stringSize < 0 || bytes.Length - offset < stringSize) {
					throw new ArgumentException("String size exceeds compact payload size.");
				}

				string stringValue = Encoding.UTF8.GetString(bytes.Slice(offset, stringSize));
				offset += stringSize;
				models.Add(new BinaryModel(intValue, floatValue, stringValue));
			}

			return models;
		}

		public static int EncodedSize(IReadOnlyList<BinaryModel> models) {
			int totalBytes = 0;
			foreach (BinaryModel model in models) {
				totalBytes += RecordHeaderBytes + Encoding.UTF8.GetByteCount(model.StringValue);
			}
			return totalBytes;
		}

		public static ulong Fingerprint(IReadOnlyList<BinaryModel> models) {
			if (models.Count == 0) {
				return 0;
			}

			int middle = models.Count / 2;
			BinaryModel[] samples = { models[0], models[middle], models[^1] };

			unchecked {
				ulong value = (ulong)models.Count;
				foreach (BinaryModel model in samples) {
					value = (value * 1_315_423_911UL) ^ (uint)model.IntValue;
					value = (value * 1_315_423_911UL) ^ BitConverter.SingleToUInt32Bits(model.FloatValue);

					ReadOnlySpan<byte> stringBytes = Encoding.UTF8.GetBytes(model.StringValue);
					value = (value * 1_315_423_911UL) ^ (ulong)stringBytes.Length;
					foreach (byte b in stringBytes) {
						value = (value * 1_099_511_628_211UL) ^ b;
					}
				}

				return value;
			}
		}

		private static void EncodeToSpan(IReadOnlyList<BinaryModel> models, Span<byte> output) {
			int offset = 0;
			foreach (BinaryModel model in models) {
				BinaryPrimitives.WriteInt32LittleEndian(output.Slice(offset, 4), model.IntValue);
				offset += 4;
				BinaryPrimitives.WriteUInt32LittleEndian(output.Slice(offset, 4), BitConverter.SingleToUInt32Bits(model.FloatValue));
				offset += 4;

				int written = Encoding.UTF8.GetBytes(model.StringValue, output[(offset + 4)..]);
				BinaryPrimitives.WriteUInt32LittleEndian(output.Slice(offset, 4), (uint)written);
				offset += 4 + written;
			}
		}
	}

	private static class U32PayloadCodec {
		public static uint[] CreateValuesForTargetBytes(int targetBytes) {
			int count = ElementCountForBytes(targetBytes);
			uint[] values = new uint[count];
			unchecked {
				for (int index = 0; index < values.Length; index++) {
					uint lhs = (uint)((ulong)index * 2_654_435_761UL);
					uint rhs = (uint)(0x9E37_79B9UL + ((ulong)index * 17UL));
					values[index] = lhs ^ rhs;
				}
			}
			return values;
		}

		public static byte[] Encode(uint[] values) {
			byte[] bytes = new byte[values.Length * sizeof(uint)];
			EncodeToSpan(values, bytes);
			return bytes;
		}

		public static void EncodeToPointer(uint[] values, byte* output, int size) {
			EncodeToSpan(values, new Span<byte>(output, size));
		}

		public static uint[] Decode(ReadOnlySpan<byte> bytes) {
			int count = ElementCountForBytes(bytes.Length);
			uint[] values = new uint[count];
			if (values.Length == 0) {
				return values;
			}

			if (BitConverter.IsLittleEndian) {
				MemoryMarshal.Cast<byte, uint>(bytes).CopyTo(values);
			} else {
				for (int index = 0; index < values.Length; index++) {
					values[index] = BinaryPrimitives.ReadUInt32LittleEndian(bytes.Slice(index * sizeof(uint), sizeof(uint)));
				}
			}
			return values;
		}

		public static ulong Fingerprint(uint[] values) {
			if (values.Length == 0) {
				return 0;
			}

			int middle = values.Length / 2;
			return (ulong)values.Length + values[0] + values[middle] + values[^1];
		}

		private static void EncodeToSpan(uint[] values, Span<byte> output) {
			Span<byte> target = output[..(values.Length * sizeof(uint))];
			if (BitConverter.IsLittleEndian) {
				MemoryMarshal.AsBytes(values.AsSpan()).CopyTo(target);
			} else {
				for (int index = 0; index < values.Length; index++) {
					BinaryPrimitives.WriteUInt32LittleEndian(target.Slice(index * sizeof(uint), sizeof(uint)), values[index]);
				}
			}
		}

		private static int ElementCountForBytes(int byteCount) {
			if (byteCount % sizeof(uint) != 0) {
				throw new ArgumentException("Byte count must align with uint32 elements.");
			}
			return byteCount / sizeof(uint);
		}
	}

	private static class BenchmarkTimer {
		public static BenchmarkMeasurement Measure(int iterations, int warmupRounds, BenchmarkTask task) {
			for (int index = 0; index < warmupRounds; index++) {
				Consume(task());
			}

			double totalMs = 0.0;
			double minMs = double.MaxValue;
			double maxMs = 0.0;

			for (int index = 0; index < iterations; index++) {
				long begin = Stopwatch.GetTimestamp();
				ulong value = task();
				long end = Stopwatch.GetTimestamp();
				Consume(value);

				double elapsedMs = (end - begin) * 1000.0 / Stopwatch.Frequency;
				totalMs += elapsedMs;
				minMs = Math.Min(minMs, elapsedMs);
				maxMs = Math.Max(maxMs, elapsedMs);
			}

			return new BenchmarkMeasurement(totalMs / iterations, minMs, maxMs);
		}
	}

	private delegate ulong BenchmarkTask();

	private static class BenchmarkReportFormatter {
		public static string Format(
			string libraryPath,
			IReadOnlyList<BenchmarkCase> cases,
			IReadOnlyList<int> modelCounts,
			IReadOnlyList<int> u32ElementCounts,
			IReadOnlyList<BenchmarkRow> rows) {
			StringBuilder report = new();
			report.Append("ffi-binary-bench .NET 8 P/Invoke benchmark\n");
			report.Append("Payload families: BinaryModel, u32 baseline\n");
			report.Append("Wire formats: little-endian BinaryModel records, raw contiguous u32 bytes\n");
			report.Append("Library: ").Append(libraryPath).Append('\n');
			report.Append(".NET: ").Append(RuntimeInformation.FrameworkDescription).Append('\n');
			report.Append("Operations:\n");
			report.Append("  BinaryModel: List<BinaryModel> -> compact bytes -> P/Invoke -> C API decode\n");
			report.Append("  BinaryModel: C API encode -> P/Invoke -> compact bytes -> List<BinaryModel>\n");
			report.Append("  u32: uint[] -> compact bytes -> P/Invoke -> C API decode\n");
			report.Append("  u32: C API encode -> P/Invoke -> compact bytes -> uint[]\n");
			report.Append("\nDatasets\n");

			for (int index = 0; index < cases.Count; index++) {
				BenchmarkCase benchmarkCase = cases[index];
				report.Append(CultureInfo.InvariantCulture,
					$"  {PadRight(benchmarkCase.Name, 8)} size={PadRight(FormatSize(benchmarkCase.TargetBytes), 10)} iterations={benchmarkCase.Iterations} binary_models={modelCounts[index]} u32_elements={u32ElementCounts[index]}\n");
			}

			AppendRowsForPayloadFamily(report, rows, PayloadFamilyBinaryModel, "BinaryModel");
			AppendRowsForPayloadFamily(report, rows, PayloadFamilyU32, "u32 Baseline");

			report.Append("\nSink: ").Append(sink).Append('\n');
			return report.ToString();
		}

		private static void AppendRowsForPayloadFamily(StringBuilder report, IReadOnlyList<BenchmarkRow> rows, string payloadFamily, string title) {
			report.Append('\n').Append(title).Append('\n');
			report.Append(CultureInfo.InvariantCulture,
				$"{PadRight("Binding", 16)} {PadRight("Operation", 35)} {PadRight("Case", 12)} {PadLeft("Iterations", 12)} {PadLeft("Elements", 12)} {PadLeft("Avg (ms)", 14)} {PadLeft("Min (ms)", 14)} {PadLeft("Max (ms)", 14)} {PadLeft("Bytes", 14)}\n");

			foreach (BenchmarkRow row in rows) {
				if (!string.Equals(row.PayloadFamily, payloadFamily, StringComparison.Ordinal)) {
					continue;
				}

				report.Append(CultureInfo.InvariantCulture,
					$"{PadRight(row.Binding, 16)} {PadRight(row.Operation, 35)} {PadRight(row.BenchmarkCase.Name, 12)} {PadLeft(row.BenchmarkCase.Iterations.ToString(CultureInfo.InvariantCulture), 12)} {PadLeft(row.ElementCount.ToString(CultureInfo.InvariantCulture), 12)} {PadLeft(row.Measurement.AverageMs.ToString("F3", CultureInfo.InvariantCulture), 14)} {PadLeft(row.Measurement.MinMs.ToString("F3", CultureInfo.InvariantCulture), 14)} {PadLeft(row.Measurement.MaxMs.ToString("F3", CultureInfo.InvariantCulture), 14)} {PadLeft(row.BenchmarkCase.TargetBytes.ToString(CultureInfo.InvariantCulture), 14)}\n");
			}
		}

		private static string FormatSize(int bytes) {
			const double kib = 1024.0;
			const double mib = 1024.0 * 1024.0;
			if (bytes >= mib) {
				return string.Format(CultureInfo.InvariantCulture, "{0:F2} MiB", bytes / mib);
			}
			if (bytes >= kib) {
				return string.Format(CultureInfo.InvariantCulture, "{0:F2} KiB", bytes / kib);
			}
			return bytes.ToString(CultureInfo.InvariantCulture) + " B";
		}

		private static string PadRight(string value, int width) {
			return value.Length >= width ? value : value.PadRight(width);
		}

		private static string PadLeft(string value, int width) {
			return value.Length >= width ? value : value.PadLeft(width);
		}
	}

	private sealed record Arguments(string LibraryPath, string? JsonPath) {
		public static Arguments Parse(string[] args) {
			string? libraryPath = null;
			string? jsonPath = null;

			for (int index = 0; index < args.Length; index++) {
				string arg = args[index];
				if (arg == "--lib") {
					index += 1;
					if (index >= args.Length) {
						throw new ArgumentException("--lib requires a path.");
					}
					libraryPath = args[index];
				} else if (arg == "--json") {
					index += 1;
					if (index >= args.Length) {
						throw new ArgumentException("--json requires a path.");
					}
					jsonPath = args[index];
				} else {
					throw new ArgumentException("Unknown argument: " + arg);
				}
			}

			libraryPath ??= DefaultLibraryPath();
			return new Arguments(Path.GetFullPath(libraryPath), jsonPath is null ? null : Path.GetFullPath(jsonPath));
		}

		private static string DefaultLibraryPath() {
			string? environmentValue = Environment.GetEnvironmentVariable("FFIBB_LIB");
			if (!string.IsNullOrWhiteSpace(environmentValue)) {
				return environmentValue;
			}

			string libraryName = SharedLibraryName();
			string cwd = Environment.CurrentDirectory;
			string baseDirectory = AppContext.BaseDirectory;
			string[] bases = { cwd, baseDirectory };

			foreach (string basePath in bases) {
				foreach (string candidate in CandidatePaths(basePath, libraryName)) {
					if (File.Exists(candidate)) {
						return candidate;
					}
				}
			}

			return CandidatePaths(cwd, libraryName).First();
		}

		private static IEnumerable<string> CandidatePaths(string basePath, string libraryName) {
			foreach (string root in CandidateRoots(basePath)) {
				yield return Path.Combine(root, "cmake-build-release", libraryName);
				yield return Path.Combine(root, "cmake-build-release", "bin", libraryName);
				yield return Path.Combine(root, "cmake-build-release", "lib", libraryName);
				yield return Path.Combine(root, "cmake-build-release", "bin", "Release", libraryName);
				yield return Path.Combine(root, "cmake-build-release", "lib", "Release", libraryName);
				yield return Path.Combine(root, "build", "native-release", "bin", libraryName);
				yield return Path.Combine(root, "build", "native-release", "lib", libraryName);
				yield return Path.Combine(root, "build", "native-release", "bin", "Release", libraryName);
				yield return Path.Combine(root, "build", "native-release", "lib", "Release", libraryName);
				yield return Path.Combine(root, "build", "dotnet-native-release", "bin", "Release", libraryName);
				yield return Path.Combine(root, "build", "dotnet-native-release", "lib", "Release", libraryName);
			}
		}

		private static IEnumerable<string> CandidateRoots(string basePath) {
			DirectoryInfo? directory = new(Path.GetFullPath(basePath));
			while (directory is not null) {
				yield return directory.FullName;
				directory = directory.Parent;
			}
		}

		private static string SharedLibraryName() {
			if (OperatingSystem.IsWindows()) {
				return "ffibb.dll";
			}
			if (OperatingSystem.IsMacOS()) {
				return "libffibb.dylib";
			}
			return "libffibb.so";
		}
	}

	private enum FfibbStatus {
		Ok = 0,
		InvalidArgument = 1,
		AllocationFailed = 2,
		DecodeError = 3,
	}

	private static class NativeMethods {
		private const string LibraryName = "ffibb";
		private static IntPtr libraryHandle;

		public static void Load(string libraryPath) {
			libraryHandle = NativeLibrary.Load(libraryPath);
			NativeLibrary.SetDllImportResolver(typeof(NativeMethods).Assembly, ResolveImport);
		}

		public static DecodeResult ProcessU32Bytes(byte* data, nuint size) {
			ulong fingerprint = 0;
			FfibbStatus status = ffibb_process_u32_bytes(data, size, &fingerprint);
			CheckStatus(status, "C API u32 decode failed");
			return new DecodeResult(size / sizeof(uint), fingerprint);
		}

		public static DecodeResult ProcessBinaryModelBytes(byte* data, nuint size) {
			nuint count = 0;
			ulong fingerprint = 0;
			FfibbStatus status = ffibb_process_binary_model_bytes(data, size, &count, &fingerprint);
			CheckStatus(status, "C API BinaryModel decode failed");
			return new DecodeResult(count, fingerprint);
		}

		public static EncodedBytes BuildSampleU32Bytes(nuint targetBytes) {
			OwnedBytes ownedBytes = default;
			ulong fingerprint = 0;
			FfibbStatus status = ffibb_build_sample_u32_bytes(targetBytes, &ownedBytes, &fingerprint);
			CheckStatus(status, "C API u32 encode failed");
			ValidateOwnedBytes(ownedBytes);
			return new EncodedBytes(ownedBytes, 0, fingerprint);
		}

		public static EncodedBytes BuildSampleBinaryModelBytes(nuint targetBytes) {
			OwnedBytes ownedBytes = default;
			nuint count = 0;
			ulong fingerprint = 0;
			FfibbStatus status = ffibb_build_sample_binary_model_bytes(targetBytes, &ownedBytes, &count, &fingerprint);
			CheckStatus(status, "C API BinaryModel encode failed");
			ValidateOwnedBytes(ownedBytes);
			return new EncodedBytes(ownedBytes, count, fingerprint);
		}

		public static void FreeBytes(OwnedBytes* bytes) {
			ffibb_free_bytes(bytes);
		}

		private static IntPtr ResolveImport(string libraryName, Assembly assembly, DllImportSearchPath? searchPath) {
			return libraryName == LibraryName ? libraryHandle : IntPtr.Zero;
		}

		private static void ValidateOwnedBytes(OwnedBytes ownedBytes) {
			if (ownedBytes.Size > int.MaxValue) {
				OwnedBytes copy = ownedBytes;
				ffibb_free_bytes(&copy);
				throw new InvalidOperationException("C API encoded payload has an unsupported size.");
			}
		}

		private static void CheckStatus(FfibbStatus status, string message) {
			if (status == FfibbStatus.Ok) {
				return;
			}

			string statusText = Marshal.PtrToStringAnsi(ffibb_status_string(status)) ?? status.ToString();
			throw new InvalidOperationException(message + ": " + statusText);
		}

		[StructLayout(LayoutKind.Sequential)]
		public struct OwnedBytes {
			public byte* Data;
			public nuint Size;
		}

		[DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
		private static extern FfibbStatus ffibb_process_u32_bytes(
			byte* data,
			nuint size,
			ulong* outFingerprint);

		[DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
		private static extern FfibbStatus ffibb_build_sample_u32_bytes(
			nuint targetBytes,
			OwnedBytes* outBytes,
			ulong* outFingerprint);

		[DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
		private static extern FfibbStatus ffibb_process_binary_model_bytes(
			byte* data,
			nuint size,
			nuint* outCount,
			ulong* outFingerprint);

		[DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
		private static extern FfibbStatus ffibb_build_sample_binary_model_bytes(
			nuint targetBytes,
			OwnedBytes* outBytes,
			nuint* outCount,
			ulong* outFingerprint);

		[DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
		private static extern void ffibb_free_bytes(OwnedBytes* bytes);

		[DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
		private static extern IntPtr ffibb_status_string(FfibbStatus status);
	}
}
