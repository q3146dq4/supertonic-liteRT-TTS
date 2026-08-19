# Supertonic-3 LiteRT GPU / Snapdragon QNN backend

> The first experimental accelerator wiring has been superseded by
> `ACCELERATION_FIX_2026-08-17.md`. Read that file for the current implementation.

Current backends:

- **CPU / XNNPACK** — existing TFLite C interpreter path.
- **GPU / LiteRT ClGl** — LiteRT 2.1.6 CompiledModel + official separate Android
  `libLiteRtClGlAccelerator.so`, with CPU partial-delegation fallback and
  `LiteRtCompiledModelIsFullyAccelerated()` reporting.
- **Qualcomm NPU / QNN** — Qualcomm `QnnDelegate` Maven integration targeting
  `HTP_BACKEND`; four delegate instances are kept alive for the four Supertonic
  TFLite graphs and their native handles are attached to the native interpreters.

The selected backend is persisted and is also used by the Android system TTS
service. QNN pre-generation is intentionally disabled until a safe additional
QNN delegate pool is implemented; normal streaming still works.
