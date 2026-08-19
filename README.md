# Supertonic LiteRT v0.1.4

Android system TTS engine for the Soniqo Supertonic-3 LiteRT 4-graph model.

## Backends in this build

- **CPU / XNNPACK (default)** — the existing native C++ LiteRT 2.1.5 + XNNPACK path is kept unchanged.
- **NNAPI / device accelerator (experimental)** — intended for MediaTek, Exynos, and Tensor devices. It requests `vector_estimator` and `vocoder` through the vendor NNAPI driver with relaxed FP16 and forbids the NNAPI CPU reference backend. On the supplied Helio G99 result it was both slow (end-to-end RTF 2.148) and numerically wrong (peak 0.029, RMS 0.002, wind-like output), so initialization alone is never treated as success.
- **GPU / speed-oriented compatible path (experimental)** — Snapdragon devices request `vector_estimator` and `vocoder` through Qualcomm QNN GPU in hybrid precision. On the tested Snapdragon 8 Elite Gen 5 device, the latest end-to-end RTF was 0.260 versus CPU's 0.188, so GPU was about 38% slower. Other devices keep `vector_estimator` on CPU because the published graph fails the classic LiteRT GPU delegate's `BROADCAST_TO`, `GATHER_ND`, and 3D `CONCATENATION` checks; only `vocoder` is requested on the classic GPU delegate with FP16 enabled.
- **Qualcomm HTP/NPU (experimental, Snapdragon only)** — the selector and QNN HTP development path are preserved. The unsafe HTP Encoder and VE stages remain blocked, and the process-crashing DSP VE path is not restored. Only the HTP/FP16 Vocoder probe is retained; its known non-finite output is rejected and the request is retried on CPU. A validated quantized export or AI Hub context binary is still required for useful full-HTP residency.

Interpreter delegates can leave unsupported operations on built-in CPU kernels. Qualcomm INFO/profiling logs are enabled and print delegated node/partition counts. The performance profile also records the selected graph stages. Treat this as graph-level acceleration, not a “100% GPU” claim. Use end-to-end RTF as the primary comparison because it normalizes synthesis time by generated audio duration; lower is faster. Stage timings explain the result but do not replace RTF.

GPU mode is intentionally not labelled “FULL”. A truly full accelerator build requires a separately re-exported or graph-surgery model whose unsupported/numerically unsafe operations are removed or rewritten.

## Correctness / safety

Accelerator outputs are checked for non-finite values after duration, encoder, every vector-estimator step and vocoder. The vocoder is also guarded against finite-but-invalid near-silence and implausibly large output. If delegate initialization or the first un-emitted chunk fails, the same request is recreated once on CPU/XNNPACK. The profile always records `requested_backend`, `active_backend`, and `accelerator_fallback`, so a CPU recovery cannot be mistaken for accelerator performance.

GPU/NNAPI delegate creation and invocation stay on the same dedicated Android thread. Vector-estimator input and output use separate buffers, then the result is copied back to the native latent tensor.

Pre-generation remains available for CPU. It is disabled for accelerator backends to avoid creating duplicate interpreters/delegates and large model-memory pressure.

## Build

Requirements: Windows 10/11, Android SDK/NDK, Git for Windows, and an installed JDK 17.

Run:

```bat
BUILD_ALL.bat
```

The setup script caches large runtime downloads under `%LOCALAPPDATA%\SupertonicLiteRT\native-cache`.

Output:

```text
Supertonic-LiteRT-v0.1.4-debug.apk
```

`BUILD_ALL.bat` performs these steps:

1. verifies that the source ZIP is complete;
2. downloads/caches the native LiteRT, Java LiteRT GPU, and Qualcomm QNN AARs;
3. creates `local.properties` from the installed Android SDK path;
4. maps the project temporarily to a short drive letter to avoid Windows/CMake path-length failures;
5. builds `:app:assembleDebug` and copies the APK to the project root.

## Project layout

- `app/` — launcher/configuration UI, TTS engine manifest, benchmark UI, icons.
- `sdk/` — Android TTS service, model downloader, settings, Java GPU/QNN runner, and JNI bridge.
- `speech-core/` — C++17 speech engine and the native Supertonic four-graph pipeline.
- `setup.sh` — downloads and stages the exact LiteRT/QNN runtime files used by the build.
- `BUILD_ALL.bat` / `INSTALL_ON_PHONE.bat` — Windows build and ADB installation entry points.

## Install and run

1. Enable USB debugging on the Android device and connect it by USB.
2. Run `INSTALL_ON_PHONE.bat` after a successful build, or install the root APK manually.
3. Launch **Supertonic LiteRT** once and download the approximately 380 MB model bundle.
4. In Android settings, choose **Supertonic LiteRT** as the default text-to-speech engine.
5. Start with **CPU / XNNPACK**. Then benchmark GPU/NNAPI/NPU with identical text, voice, speed, steps, and chunk size. Compare end-to-end RTF and confirm that `Active backend` did not fall back to CPU.

## Build diagnostics

- `AttachCurrentThread` / `JNIEnv **` error with NDK 29: fixed in this source by passing `JNIEnv**` to the Android C++ JNI wrapper.
- `SDK XML version 4` message: Android SDK command-line tools and Android Studio components are out of sync. It is a warning in the supplied log; update them from Android Studio's SDK Manager if desired.
- KISS FFT `.c` compiled as C++ and unused Kokoro helper messages: warnings from the bundled `speech-core` build, not the cause of the supplied failure.
- `extractNativeLibs` warning: the manifest attribute was removed. `useLegacyPackaging = true` in Gradle remains authoritative because QNN needs extracted native libraries.

## App identity

- App name: **Supertonic LiteRT**
- Version name: **0.1.4**
- Launcher / installer / TTS service icon: the blue-purple waveform icon supplied for this project.
