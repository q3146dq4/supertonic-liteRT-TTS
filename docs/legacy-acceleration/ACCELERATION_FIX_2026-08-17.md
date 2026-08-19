# GPU / Qualcomm QNN initialization fix — 2026-08-17

This patch replaces the first experimental accelerator wiring that failed on the
OnePlus 15 with `CreateCompiledModel failed (status=504)`.

## What was wrong in the first accelerator build

### GPU

The first build treated the LiteRT GPU accelerator as though it were bundled in
the generic runtime package. On Android, Google's current prebuilt C++ setup
publishes the GPU accelerator separately from `libLiteRt.so`.

This patch uses matching **LiteRT 2.1.6** files:

- `libLiteRt.so`
- `libLiteRtClGlAccelerator.so` (Android OpenCL + OpenGL accelerator)

`setup.sh` downloads the official prebuilts and packages the GPU accelerator in
the APK. The Android `nativeLibraryDir` and accelerator cache path are passed to
the native engine so LiteRT can discover the accelerator at runtime.

GPU CompiledModel requests **GPU + CPU fallback**. After compilation each graph
is checked with `LiteRtCompiledModelIsFullyAccelerated()`. The profile therefore
reports either:

- `GPU/LiteRT ClGl FULL [...]`
- `GPU/LiteRT ClGl PARTIAL+CPU [...]`

rather than silently calling a partially delegated model "GPU only".

### Qualcomm NPU

The first build requested generic `kLiteRtHwAcceleratorNpu` through
`CompiledModel` without wiring the required Qualcomm dispatch/vendor runtime
path. That was the wrong integration for the Maven QNN runtime we package.

The new path follows the Qualcomm/Google Android QNN Delegate integration:

- `com.qualcomm.qti:qnn-runtime:2.49.0`
- `com.qualcomm.qti:qnn-litert-delegate:2.49.0`
- `QnnDelegate.Options.BackendType.HTP_BACKEND`
- `setSkelLibraryDir(applicationInfo.nativeLibraryDir)`
- persistent per-graph QNN compilation cache

Four QNN delegates are created, one for each independent Supertonic graph:

1. duration predictor
2. text encoder
3. vector estimator
4. vocoder

Their native delegate handles are attached to the corresponding native TFLite
interpreters. Kotlin owns the delegate objects and keeps them alive until after
the native interpreters are destroyed.

## Android packaging changes

`useLegacyPackaging = true` is enabled so vendor native libraries are extracted
as real files and are available through `applicationInfo.nativeLibraryDir`.
`libcdsprpc.so` is declared optional for Qualcomm CDSP/HTP communication, while
`libOpenCL.so` remains optional for GPU devices.

## Diagnostics

Initialization errors now identify the individual graph where possible, e.g.:

- `Supertonic[duration] GPU compile: ...`
- `Supertonic[vector_estimator]: AllocateTensors failed after QNN/HTP delegation`

LiteRT errors also include the textual status name when the runtime provides it.
This should make a remaining device-specific failure much easier to diagnose
than the old bare `status=504` message.

## Pre-generation in QNN mode

QNN pre-generation is deliberately disabled in this build. Each concurrently
running Supertonic engine needs its own four delegate instances; sharing one QNN
delegate between multiple simultaneous interpreters is unsafe. Normal streaming
synthesis remains enabled. CPU and GPU pre-generation are unchanged.

Once the main QNN path is verified on-device, a pool of extra QNN delegate sets
can be added for safe NPU pre-generation.

## Recommended first test

1. Build/install cleanly.
2. Use the same model files already downloaded by the app.
3. Select **GPU / LiteRT** and press START once.
4. Capture the full profile or exact initialization error.
5. On Snapdragon, select **Qualcomm NPU / QNN** and press START once.
6. Capture the full profile or exact initialization error.

On Helio G99, test CPU and GPU only; Qualcomm QNN is not applicable.
