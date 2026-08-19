# Accelerator runtime failure fix — 2026-08-18

## Device failures reproduced from the supplied screenshots

### Qualcomm QNN

The initial HTP delegate failed first at `text_encoder`. After the encoder was
moved to CPU, the supplied follow-up run proved that the public FP32
`vector_estimator` also returns NaN/Inf at step 1 on HTP/FP16. Qualcomm HTP
exposes FP16 or quantized precision for this bundle; there is no HTP FP32 mode
in QNN Delegate 2.49.0.

The later HTP-vocoder-only run proved that `vocoder` also returns non-finite
output. The public FP32 bundle therefore has no useful, numerically valid HTP
stage. Fix in v0.1.3: remove the NPU selector, prevent HTP delegate construction,
and migrate any saved Qualcomm NPU setting to CPU/XNNPACK. The interim QNN DSP
VE probe also remains removed because it caused a process-level crash on the
supplied Snapdragon 8 Elite Gen 5 device.

### GPU

The classic LiteRT GPU delegate rejected the published `vector_estimator`:

- `BROADCAST_TO` unsupported;
- `GATHER_ND` unsupported;
- incompatible `TANH` constant input;
- 3D `CONCATENATION` (`1x50x256`) where the delegate requires 4D BHWC;
- only 160 operations were GPU candidates while 1,640 remained on CPU.

Fix: do not submit this graph to the classic GPU delegate. Snapdragon devices
request `vector_estimator` and `vocoder` through QNN GPU hybrid precision. The
earlier FP32 route synthesized correctly on the supplied Snapdragon 8 Elite Gen 5 device but was
about 46% slower end-to-end than CPU/XNNPACK, so it is no longer the selected
speed profile. If QNN GPU rejects vocoder, the app probes vocoder once through
the classic LiteRT GPU delegate. Non-Qualcomm devices keep VE on XNNPACK and
probe only vocoder with FP16 enabled.

### MediaTek / Exynos / Tensor

Qualcomm QNN cannot accelerate these devices. This build adds an experimental
NNAPI route that requests VE and vocoder through the device vendor driver with
relaxed FP16 and disables the NNAPI CPU reference backend. This is a benchmark
candidate, not a speed claim: end-to-end RTF must be compared on each G99,
Kompanio, Exynos, or Tensor device. Stage times are diagnostic secondary values.

### Initial speed display

The speed `SeekBar` previously rendered its default progress (`0.25`) before
restored settings were applied. The stored value did control synthesis, but the
label stayed stale because the listener was attached later. The initial
progress is now `1.00`, and restoring settings explicitly refreshes the label.

## Honest backend reporting

The Java runner reports `hasDuration`, `hasEncoder`, `hasVector`, and
`hasVocoder` to native C++. Native C++ loads an XNNPACK interpreter only for
stages the accelerator runner did not accept. The performance profile records
the actual graph-level mapping.

QNN INFO and BASIC profiling are enabled. Device logs contain the QNN line
showing how many nodes and partitions were delegated. A backend selection alone
is not treated as proof of full GPU execution.

## Validation completed here

- Kotlin 2.2.21 compilation of the accelerator runner against the exact LiteRT
  1.4.2 and QNN Delegate 2.49.0 AAR APIs: passed.
- C++17 syntax compilation of `litert_supertonic_tts.cpp`: passed.
- Full Android APK/device execution still requires the Android SDK/NDK and the
  target devices. In particular, NNAPI speed remains a device test.

## Device log filter

```bat
adb logcat -c
adb logcat SupertonicAccel:I SupertonicTTS:I QnnTFLiteDelegate:I tflite:I *:S
```

For each backend, synthesize the same short sentence once and retain the first
failure plus every line containing `nodes delegated` or `partitions`.
