# Build fix 3 — LiteRT 2.1.6 legacy ABI linker failure

The previous build reached native linking, then failed on symbols such as:

- `TfLiteModelCreateFromFile`
- `TfLiteInterpreterCreate`
- `TfLiteInterpreterInvoke`
- `TfLiteXNNPackDelegateCreate`

The project still uses the classic TfLite C Interpreter ABI for CPU/XNNPACK and
for the classic Qualcomm QNN delegate path. The LiteRT 2.1.6 standalone runtime
used in the previous revision no longer exports those legacy symbols.

This revision therefore:

1. Pins the native Android LiteRT runtime to **2.1.5**, from the Google Maven AAR
   that was already known to link this project's legacy CPU path.
2. Takes `libLiteRt.so` and `libLiteRtClGlAccelerator.so` from the **same 2.1.5 AAR**.
3. Keeps the GPU path on the new `CompiledModel` API.
4. Keeps Qualcomm QNN on the classic delegate path.
5. Marks unrelated `speech_kokoro_litert_bench` and `speech_litert_tensor_bench`
   executables `EXCLUDE_FROM_ALL`, so an Android APK build only needs the JNI
   library and does not waste time linking command-line benchmark tools.

The Java `litert-api:1.4.2` dependency remains API-only and exists solely to
provide `org.tensorflow.lite.Delegate` for Qualcomm's `QnnDelegate` wrapper.
It does not replace the native LiteRT runtime.
