package com.ffibb.android;

import dalvik.annotation.optimization.FastNative;
import java.nio.ByteBuffer;

public final class BenchmarkBridge {
  static {
    System.loadLibrary("ffibb");
  }

  private BenchmarkBridge() {
  }

  public static native long nativeProcessU32(byte[] bytes);

  public static native byte[] nativeBuildSampleU32(int targetBytes);

  public static native long nativeProcessBinaryModels(byte[] bytes);

  public static native byte[] nativeBuildSampleBinaryModels(int targetBytes);

  public static native long nativeProcessU32FromDirectBuffer(ByteBuffer bytes, int size);

  public static native int nativeBuildSampleU32ToDirectBuffer(int targetBytes, ByteBuffer output);

  public static native long nativeProcessBinaryModelsFromDirectBuffer(ByteBuffer bytes, int size);

  public static native int nativeBuildSampleBinaryModelsToDirectBuffer(int targetBytes, ByteBuffer output);

  @FastNative
  public static native long nativeProcessU32FromDirectBufferFast(ByteBuffer bytes, int size);

  @FastNative
  public static native int nativeBuildSampleU32ToDirectBufferFast(int targetBytes, ByteBuffer output);

  @FastNative
  public static native long nativeProcessBinaryModelsFromDirectBufferFast(ByteBuffer bytes, int size);

  @FastNative
  public static native int nativeBuildSampleBinaryModelsToDirectBufferFast(int targetBytes, ByteBuffer output);
}
